package com.clinicos.service.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * Utility for hashing high-entropy tokens (like JWTs) using SHA-256.
 * Unlike passwords, JWTs are cryptographically random and don't need BCrypt.
 */
public final class TokenHashUtil {

    private TokenHashUtil() {
        // Utility class
    }

    /**
     * Hash a token using SHA-256 and return Base64-encoded result.
     */
    public static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Verify a token against a stored hash.
     */
    public static boolean verify(String token, String storedHash) {
        String computedHash = hash(token);
        return computedHash.equals(storedHash);
    }
}
