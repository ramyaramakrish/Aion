package com.aion.util;

public final class JsonSpecExtractor {

    private JsonSpecExtractor() {}

    public static String extractJsonObject(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Empty model response");
        }
        String s = raw.trim();
        if (s.startsWith("```")) {
            int fence = s.indexOf('\n');
            if (fence > 0) {
                s = s.substring(fence + 1).trim();
            }
            if (s.endsWith("```")) {
                s = s.substring(0, s.length() - 3).trim();
            }
        }
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("No JSON object found in model output");
        }
        return s.substring(start, end + 1);
    }
}
