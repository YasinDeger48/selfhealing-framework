package com.selfhealing.healer.core;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrivacyFilterTest {

    private final PrivacyFilter filter = new PrivacyFilter(true, "");

    private String mask(String s) {
        return filter.session().mask(s);
    }

    @Test
    void masksPersonalAndSecretData() {
        assertEquals("Signed in as [EMAIL]", mask("Signed in as jane.doe@example.com"));
        assertEquals("Call [PHONE]", mask("Call +90 532 123 45 67"));
        assertEquals("Call [PHONE]", mask("Call 0850 000 00 00"));
        assertEquals("Card [CARD]", mask("Card 4111 1111 1111 1111"));
        assertEquals("IBAN [IBAN]", mask("IBAN TR33 0006 1005 1978 6457 8413 26"));
        assertEquals("ID [NATIONAL_ID]", mask("ID 10000000146"));
        assertEquals("Order SL-[NUMBER]", mask("Order SL-12345678"));
        assertEquals("Bearer [TOKEN]", mask("Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0In0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U"));
        assertEquals("key [TOKEN]", mask("key a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6q7"));
    }

    @Test
    void leavesOrdinaryPageContentAlone() {
        for (String s : new String[] {"₺1,299.00", "34000", "2026-09-26", "Add to Cart", "product-card-8",
                "login-forgot-password-link", "4111 1111 1111 1112", "Quantity 10", "Mon-Fri 09:00 - 18:00"}) {
            assertEquals(s, mask(s), "should not be masked: " + s);
        }
    }

    @Test
    void countsWhatWasMaskedPerRequest() {
        PrivacyFilter.Session session = filter.session();
        session.mask("a@b.io and c@d.io");
        session.mask("0850 000 00 00");
        assertEquals(Map.of("EMAIL", 2, "PHONE", 1), session.counts());
    }

    @Test
    void customRulesAndSwitchOff() {
        PrivacyFilter custom = new PrivacyFilter(true, "CUSTOMER=CUST-\\d{4}");
        assertEquals("Customer [CUSTOMER]", custom.session().mask("Customer CUST-0042"));

        PrivacyFilter off = new PrivacyFilter(false, "");
        assertFalse(off.enabled());
        assertEquals("jane@example.com", off.session().mask("jane@example.com"));
    }

    @Test
    void luhnSeparatesCardsFromOtherLongNumbers() {
        assertTrue(PrivacyFilter.luhn("4111111111111111"));
        assertFalse(PrivacyFilter.luhn("4111111111111112"));
    }
}
