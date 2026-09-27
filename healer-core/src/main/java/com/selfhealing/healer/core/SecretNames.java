package com.selfhealing.healer.core;

import java.util.Set;

/**
 * Whether an element name suggests a secret value (its typed text is then masked in steps and reports).
 * Whole words only: "LoginPage.password" and "userPwd" are secret, "passengerSurname" and "shippingAddress" are not.
 */
public final class SecretNames {

    private static final Set<String> WORDS = Set.of("password", "passwd", "pwd", "pass", "passcode", "passphrase",
            "parola", "sifre", "secret", "token", "pin", "otp", "cvv", "cvc", "apikey", "credential", "credentials",
            "passwort", "kennwort", "пароль", "パスワード", "كلمة");

    private SecretNames() {
    }

    public static boolean isSecret(String elementName) {
        if (elementName == null) return false;
        String spaced = elementName.replaceAll("([a-z0-9])([A-Z])", "$1 $2");
        for (String t : Similarity.normalize(spaced).split("[^\\p{L}\\p{N}]+")) {
            if (WORDS.contains(t)) return true;
        }
        // "apiKey" -> "api key"
        return Similarity.normalize(spaced).matches(".*\\bapi key\\b.*");
    }
}
