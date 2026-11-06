package com.iamgwc.gwcext.format;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.time.Instant;
import java.util.List;

// One entry in a .gwc document's history log; each line of gwc/history.jsonl deserializes to one of these two
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "op")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Op.Paste.class, name = "paste"),
        @JsonSubTypes.Type(value = Op.Edit.class, name = "edit")
})
public sealed interface Op permits Op.Paste, Op.Edit {

    String id();

    Instant ts();

    /* replacedLength: characters overwritten by this paste, or 0 for a plain insertion
     * atLine/endLine: lines the pasted words landed on (leading/trailing whitespace ignored), display only; 0 on older files
     */
    record Paste(
            String id, Instant ts, int at, int replacedLength, String text,
            @JsonProperty("atLine") int atLineNumber,
            @JsonProperty("endLine") int endLineNumber) implements Op {

        // Absolute offsets of the pasted text with leading/trailing whitespace ignored (the whole text if it's all whitespace)
        @JsonIgnore
        public int contentStart() {
            int lead = leadingWhitespace(text);
            return lead == text.length() ? at : at + lead;
        }

        @JsonIgnore
        public int contentEnd() {
            int lead = leadingWhitespace(text);
            return lead == text.length() ? at + text.length() : at + text.length() - trailingWhitespace(text);
        }

        static int leadingWhitespace(String s) {
            int i = 0;
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
            return i;
        }

        static int trailingWhitespace(String s) {
            int i = 0;
            while (i < s.length() && Character.isWhitespace(s.charAt(s.length() - 1 - i))) {
                i++;
            }
            return i;
        }
    }

    // One save's worth of typed changes. Pastes get their own Paste entry instead
    record Edit(String id, Instant ts, List<DiffEntry> diffs) implements Op {
    }

    /* One changed range inside an Edit. Only range/oldText/newText matter for replay; the line fields
     * exist so the detail view can show the whole sentence a change landed in, and are computed with
     * leading whitespace skipped so an edit is reported as starting where its words start
     */
    record DiffEntry(
            String span,
            int[] range,
            @JsonProperty("old") String oldText,
            @JsonProperty("new") String newText,
            @JsonProperty("oldLineNo") int oldLineNumber,
            @JsonProperty("oldLine") String oldLine,
            @JsonProperty("oldLineRange") int[] oldLineRange,
            @JsonProperty("newLineNo") int newLineNumber,
            @JsonProperty("newLine") String newLine,
            @JsonProperty("newLineRange") int[] newLineRange) {

        // Last line the new text reaches; trailing whitespace is ignored so a trailing newline isn't counted as a line
        @JsonIgnore
        public int newEndLineNumber() {
            int from = Math.max(0, Math.min(newLineRange[0], newLine.length()));
            int to = Math.max(from, Math.min(newLineRange[1], newLine.length()));
            while (to > from && Character.isWhitespace(newLine.charAt(to - 1))) {
                to--;
            }
            int extraLines = 0;
            for (int i = from; i < to; i++) {
                if (newLine.charAt(i) == '\n') {
                    extraLines++;
                }
            }
            return newLineNumber + extraLines;
        }
    }
}