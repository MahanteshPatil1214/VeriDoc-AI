package com.veridoc.ai.common.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Content hashing helpers used for duplicate detection and chunk identity. */
public final class Hashing {

    private Hashing() {
    }

    public static String sha256Hex(byte[] content) {
        MessageDigest digest = messageDigest();
        return HexFormat.of().formatHex(digest.digest(content));
    }

    public static String sha256Hex(String content) {
        return sha256Hex(content.getBytes(StandardCharsets.UTF_8));
    }

    private static MessageDigest messageDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }
}