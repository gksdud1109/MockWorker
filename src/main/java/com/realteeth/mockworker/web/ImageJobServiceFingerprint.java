package com.realteeth.mockworker.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Matches {@code ImageJobService#fingerprint} so the auto-key path dedupes against real keys. */
final class ImageJobServiceFingerprint {
    private ImageJobServiceFingerprint() {}

    static String of(String imageUrl) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(("v1|" + imageUrl).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
