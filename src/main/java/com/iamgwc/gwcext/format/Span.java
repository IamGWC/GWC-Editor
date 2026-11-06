package com.iamgwc.gwcext.format;

// A contiguous run of the document's current text, tagged with whatever created it (a paste or "typed"). In-memory only; the op log is the durable record
public final class Span {

    public static final String TYPED_ORIGIN = "typed";

    private final String originId;
    private final Origin originType;
    private StringBuilder text;

    public enum Origin { PASTE, TYPED }

    public Span(String originId, Origin originType, String initialText) {
        this.originId = originId;
        this.originType = originType;
        this.text = new StringBuilder(initialText);
    }

    public String originId() {
        return originId;
    }

    public Origin originType() {
        return originType;
    }

    public String text() {
        return text.toString();
    }

    public int length() {
        return text.length();
    }

    // Public (not package private) because DiffEngine in a different package needs to mutate span contents
    public void replaceRange(int start, int end, String replacement) {
        text.replace(start, end, replacement);
    }
}