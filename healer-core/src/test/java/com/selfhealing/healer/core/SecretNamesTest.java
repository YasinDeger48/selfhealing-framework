package com.selfhealing.healer.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecretNamesTest {

    @Test
    void wholeWordsOnly() {
        for (String secret : new String[] {"LoginPage.password", "userPwd", "Signup.confirmPassword", "Card.cvv", "Otp.code",
                "Settings.apiKey", "Login.parola", "Giris.sifre", "Bank.pin", "passwordConfirmation"}) {
            assertTrue(SecretNames.isSecret(secret), secret);
        }
        for (String plain : new String[] {"Lab.passengerSurname", "Checkout.shippingAddress", "Map.spinner", "Tokens.list.count",
                "Header.compass", "Form.pinnedNote", "Profile.username"}) {
            assertFalse(SecretNames.isSecret(plain), plain);
        }
    }
}
