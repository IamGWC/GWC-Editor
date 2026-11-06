package com.iamgwc.gwcext.format;

// Prevents a split point from landing inside a UTF-16 surrogate pair
public final class CodePoints {

    private CodePoints() {
    }

    // Nudges index back by one if it falls between a high and low surrogate
    public static int snapBoundary(String text, int index) {
        if (index <= 0 || index >= text.length()) {
            return index;
        }
        if (Character.isLowSurrogate(text.charAt(index)) && Character.isHighSurrogate(text.charAt(index - 1))) {
            return index - 1;
        }
        return index;
    }

    public static int[] snapRange(String text, int start, int end) {
        return new int[]{snapBoundary(text, start), snapBoundary(text, end)};
    }
}