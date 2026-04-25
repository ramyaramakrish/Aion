package com.aion.util;

public final class JsonSpecExtractor {

    private JsonSpecExtractor() {
        // Utility class
    }

    /**
     * Extracts the first valid JSON object from a string that may contain markdown fences
     * or other text.
     *
     * @param text The string potentially containing a JSON object.
     * @return The extracted JSON object as a string, or the original text if no object is found.
     */
    public static String extractJsonObject(String text) {
        if (text == null || text.isBlank()) {
            return "{}";
        }

        int firstBrace = text.indexOf('{');
        int lastBrace = text.lastIndexOf('}');

        return (firstBrace != -1 && lastBrace != -1 && lastBrace > firstBrace) ? text.substring(firstBrace, lastBrace + 1) : text;
    }
}