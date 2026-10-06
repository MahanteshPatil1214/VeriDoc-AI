package com.veridoc.ai.common.security;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Filename hardening. Uploaded filenames are used for display only; the actual
 * storage path is always derived from a server-generated UUID.
 */
public final class FileNameSanitizer {

    private static final int MAX_LENGTH = 200;

    /** RFC 6266 control characters plus separators that enable path traversal. */
    private static final Pattern UNSAFE = Pattern.compile("[\\p{Cntrl}<>:\"/\\\\|?*]");

    private FileNameSanitizer() {
    }

    /** Produces a safe display name; never throws. */
    public static String sanitizeDisplayName(String rawFilename) {
        if (rawFilename == null || rawFilename.isBlank()) {
            return "document.pdf";
        }
        // Strip any directory component the client may have sent.
        String base = rawFilename.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        String cleaned = UNSAFE.matcher(base).replaceAll("_").strip();
        // Defeat "..", reserved Windows device names, and leading dots.
        while (cleaned.startsWith(".")) {
            cleaned = cleaned.substring(1);
        }
        if (cleaned.equalsIgnoreCase(".") || cleaned.equalsIgnoreCase("..")) {
            cleaned = "document";
        }
        if (isReservedWindowsName(cleaned)) {
            cleaned = "_" + cleaned;
        }
        cleaned = cleaned.replaceAll("\\s+", " ").strip();
        if (cleaned.isEmpty()) {
            cleaned = "document.pdf";
        }
        if (cleaned.length() > MAX_LENGTH) {
            int dot = cleaned.lastIndexOf('.');
            String ext = dot > 0 && cleaned.length() - dot <= 12 ? cleaned.substring(dot) : "";
            int keep = Math.max(1, MAX_LENGTH - ext.length());
            cleaned = cleaned.substring(0, keep) + ext;
        }
        return cleaned;
    }

    /** Returns the lower-cased extension without the dot, or an empty string. */
    public static String extensionOf(String rawFilename) {
        if (rawFilename == null) {
            return "";
        }
        String base = rawFilename.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        int dot = base.lastIndexOf('.');
        if (dot < 0 || dot == base.length() - 1) {
            return "";
        }
        return base.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean isReservedWindowsName(String name) {
        int dot = name.indexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        return switch (stem.toUpperCase(Locale.ROOT)) {
            case "CON", "PRN", "AUX", "NUL",
                 "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
                 "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9" -> true;
            default -> false;
        };
    }
}