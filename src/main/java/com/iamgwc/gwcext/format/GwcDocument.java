package com.iamgwc.gwcext.format;

import com.iamgwc.gwcext.diff.DiffEngine;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class GwcDocument {

    private final String fileId;
    private final List<Op> opLog = new ArrayList<>();
    private final List<Span> spans = new ArrayList<>();
    private int nextOpNumber = 1;
    private final Map<String, List<int[]>> anchors = new LinkedHashMap<>();

    // Text as of the last save or paste; the diff baseline the next save compares against
    private String lastSavedText = "";

    public GwcDocument(String fileId) {
        this.fileId = fileId;
    }

    public static GwcDocument createNew() {
        return new GwcDocument(FileIds.newFileId());
    }

    public String fileId() {
        return fileId;
    }

    public List<Op> opLog() {
        return List.copyOf(opLog);
    }

    public String currentText() {
        StringBuilder sb = new StringBuilder();
        for (Span s : spans) {
            sb.append(s.text());
        }
        return sb.toString();
    }

    public List<Span> spans() {
        return spans;
    }

    private String nextId() {
        return "o" + (nextOpNumber++);
    }

    // replacedLength characters at at (its not a typo), if any, are overwritten by text, a paste saves immediately, not on the next save
    public Op.Paste recordPaste(int at, int replacedLength, String text) {
        String beforeText = currentText();
        int[] snapped = CodePoints.snapRange(beforeText, at, at + replacedLength);
        at = snapped[0];
        replacedLength = snapped[1] - snapped[0];

        String id = nextId();
        Op.Paste unlined = new Op.Paste(id, Instant.now(), at, replacedLength, text, 0, 0);
        // Line range of the pasted words, ignoring leading/trailing whitespace
        int firstWord = unlined.contentStart() - at;
        int lastWord = unlined.contentEnd() - at;
        int atLine = 1 + countChar(beforeText, '\n', 0, at) + countChar(text, '\n', 0, firstWord);
        int endLine = atLine + countChar(text, '\n', firstWord, lastWord);
        Op.Paste op = new Op.Paste(id, unlined.ts(), at, replacedLength, text, atLine, endLine);
        opLog.add(op);

        if (replacedLength > 0) {
            deleteRange(at, replacedLength);
        }
        Span span = new Span(id, Span.Origin.PASTE, text);
        insertSpanAt(at, span);
        lastSavedText = currentText();
        anchors.put(id, newAnchorList(List.of(new int[]{at, at + text.length()})));
        return op;
    }

    public Op.Paste recordPaste(int at, String text) {
        return recordPaste(at, 0, text);
    }

    private static int countChar(String text, char c, int from, int to) {
        int count = 0;
        for (int i = from; i < to; i++) {
            if (text.charAt(i) == c) {
                count++;
            }
        }
        return count;
    }

    // Called by recordPaste and replay so a paste behaves identically live or reconstructed
    private void deleteRange(int at, int length) {
        if (length <= 0 || spans.isEmpty()) {
            return;
        }
        int running = 0;
        int spanIndex = -1;
        int localStart = 0;
        for (int i = 0; i < spans.size(); i++) {
            int len = spans.get(i).length();
            if (at <= running + len) {
                spanIndex = i;
                localStart = at - running;
                break;
            }
            running += len;
        }
        if (spanIndex == -1) {
            return;
        }

        int remaining = length;
        Span first = spans.get(spanIndex);
        int deleteHere = Math.min(first.length() - localStart, remaining);
        first.replaceRange(localStart, localStart + deleteHere, "");
        remaining -= deleteHere;

        int cursor = spanIndex + 1;
        while (remaining > 0 && cursor < spans.size()) {
            Span next = spans.get(cursor);
            int take = Math.min(next.length(), remaining);
            next.replaceRange(0, take, "");
            remaining -= take;
            cursor++;
        }
    }

    private void insertSpanAt(int at, Span newSpan) {
        int running = 0;
        for (int i = 0; i < spans.size(); i++) {
            Span s = spans.get(i);
            int len = s.length();
            if (at <= running + len) {
                int local = at - running;
                if (local == 0) {
                    spans.add(i, newSpan);
                } else if (local == len) {
                    spans.add(i + 1, newSpan);
                } else {
                    String full = s.text();
                    String before = full.substring(0, local);
                    String after = full.substring(local);
                    Span left = new Span(s.originId(), s.originType(), before);
                    Span right = new Span(s.originId(), s.originType(), after);
                    spans.remove(i);
                    spans.add(i, left);
                    spans.add(i + 1, newSpan);
                    spans.add(i + 2, right);
                }
                return;
            }
            running += len;
        }
        if (spans.isEmpty() || at >= running) {
            spans.add(newSpan);
        }
    }

    // Called at save time; diffs the live text against the last save and appends one Edit op if anything changed
    public Optional<Op.Edit> recordSaveIfChanged(String liveText) {
        if (liveText.equals(lastSavedText)) {
            return Optional.empty();
        }
        if (spans.isEmpty() && !lastSavedText.isEmpty()) {
            spans.add(new Span(Span.TYPED_ORIGIN, Span.Origin.TYPED, lastSavedText));
        }
        if (spans.isEmpty()) {
            // replay() must create this same baseline span, or reconstruction disagrees with live editing
            spans.add(new Span(Span.TYPED_ORIGIN, Span.Origin.TYPED, ""));
        }

        DiffEngine.Result result = DiffEngine.diffAndApply(lastSavedText, liveText, spans);
        var diffs = result.entries();
        if (diffs.isEmpty()) {
            lastSavedText = liveText;
            return Optional.empty();
        }

        Op.Edit op = new Op.Edit(nextId(), Instant.now(), diffs);
        opLog.add(op);
        anchors.put(op.id(), newAnchorList(result.newRanges()));
        lastSavedText = liveText;
        return Optional.of(op);
    }

    // Removes exactly one entry from the log; spans and text are not touched. A later replay that depended on it
    // just reconciles against the document body on open (see GwcReader), so text is never lost
    public boolean deleteHistoryEntry(String opId) {
        anchors.remove(opId);
        return opLog.removeIf(op -> op.id().equals(opId));
    }

    public List<int[]> anchorsOf(String opId) {
        return anchors.getOrDefault(opId, List.of());
    }

    public Map<String, List<int[]>> anchorsSnapshot() {
        Map<String, List<int[]>> snapshot = new LinkedHashMap<>();
        for (Op op : opLog) {
            List<int[]> ranges = anchors.get(op.id());
            if (ranges != null) {
                snapshot.put(op.id(), newAnchorList(ranges));
            }
        }
        return snapshot;
    }

    public void restoreAnchors(Map<String, List<int[]>> saved) {
        for (Op op : opLog) {
            List<int[]> ranges = saved.get(op.id());
            if (ranges == null || ranges.size() != expectedAnchorCount(op)) {
                continue;
            }
            anchors.put(op.id(), newAnchorList(ranges));
        }
    }

    public void fillMissingAnchors() {
        String text = currentText();
        for (Op op : opLog) {
            if (anchors.containsKey(op.id())) {
                continue;
            }
            List<int[]> ranges = new ArrayList<>();
            switch (op) {
                case Op.Paste p -> {
                    int start = Math.min(p.at(), text.length());
                    int end = Math.min(p.at() + p.text().length(), text.length());
                    ranges.add(new int[]{start, Math.max(start, end)});
                }
                case Op.Edit e -> {
                    for (Op.DiffEntry d : e.diffs()) {
                        int lineStart = offsetOfLine(text, d.newLineNumber());
                        int start = Math.min(lineStart + d.newLineRange()[0], text.length());
                        int end = Math.min(lineStart + d.newLineRange()[1], text.length());
                        ranges.add(new int[]{start, Math.max(start, end)});
                    }
                }
            }
            anchors.put(op.id(), ranges);
        }
    }

    public void textChanged(String oldText, String newText) {
        int limit = Math.min(oldText.length(), newText.length());
        int prefix = 0;
        while (prefix < limit && oldText.charAt(prefix) == newText.charAt(prefix)) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < limit - prefix
                && oldText.charAt(oldText.length() - 1 - suffix) == newText.charAt(newText.length() - 1 - suffix)) {
            suffix++;
        }
        int removed = oldText.length() - prefix - suffix;
        int inserted = newText.length() - prefix - suffix;
        if (removed == 0 && inserted == 0) {
            return;
        }
        for (List<int[]> ranges : anchors.values()) {
            for (int[] range : ranges) {
                shiftRange(range, prefix, removed, inserted);
            }
        }
    }

    private static void shiftRange(int[] range, int position, int removed, int inserted) {
        int start = range[0];
        int end = range[1];
        int changeEnd = position + removed;
        int delta = inserted - removed;
        if (changeEnd <= start) {
            start += delta;
            end += delta;
        } else if (position < end) {
            if (position <= start) {
                start = position + inserted;
                end = changeEnd >= end ? start : end + delta;
            } else {
                end = changeEnd >= end ? position + inserted : end + delta;
            }
        }
        range[0] = start;
        range[1] = Math.max(start, end);
    }

    private static int expectedAnchorCount(Op op) {
        return switch (op) {
            case Op.Paste p -> 1;
            case Op.Edit e -> e.diffs().size();
        };
    }

    private static List<int[]> newAnchorList(List<int[]> source) {
        List<int[]> copy = new ArrayList<>();
        for (int[] range : source) {
            copy.add(new int[]{range[0], range[1]});
        }
        return copy;
    }

    private static int offsetOfLine(String text, int lineNumber) {
        int offset = 0;
        for (int line = 1; line < lineNumber; line++) {
            int next = text.indexOf('\n', offset);
            if (next == -1) {
                return text.length();
            }
            offset = next + 1;
        }
        return offset;
    }

    // Rebuilds a document purely from its op log; used on open
    public static GwcDocument replay(String fileId, List<Op> ops) {
        GwcDocument doc = new GwcDocument(fileId);
        for (Op op : ops) {
            doc.opLog.add(op);
            int n = Integer.parseInt(op.id().substring(1));
            if (n >= doc.nextOpNumber) {
                doc.nextOpNumber = n + 1;
            }
            switch (op) {
                case Op.Paste p -> {
                    if (p.replacedLength() > 0) {
                        doc.deleteRange(p.at(), p.replacedLength());
                    }
                    Span span = new Span(p.id(), Span.Origin.PASTE, p.text());
                    doc.insertSpanAt(p.at(), span);
                }
                case Op.Edit e -> {
                    // Must match recordSaveIfChanged's lazy baseline creation or the first Edit's text gets dropped
                    if (doc.spans.isEmpty()) {
                        doc.spans.add(new Span(Span.TYPED_ORIGIN, Span.Origin.TYPED, ""));
                    }
                    // Applied highest-range-first, independent of storage order, same reason as DiffEngine:
                    // an earlier mutation must never invalidate a later diff's already recorded offset
                    List<Op.DiffEntry> ordered = new ArrayList<>(e.diffs());
                    ordered.sort((a, b) -> Integer.compare(b.range()[0], a.range()[0]));
                    for (Op.DiffEntry d : ordered) {
                        applyDiffEntryDuringReplay(doc.spans, d);
                    }
                }
            }
        }
        doc.lastSavedText = doc.currentText();
        return doc;
    }

    private static void applyDiffEntryDuringReplay(List<Span> spans, Op.DiffEntry d) {
        for (Span s : spans) {
            if (s.originId().equals(d.span())) {
                // Clamped: after a history entry is deleted, a later diff's recorded range can point past the text
                int start = Math.max(0, Math.min(d.range()[0], s.length()));
                int end = Math.max(start, Math.min(d.range()[1], s.length()));
                s.replaceRange(start, end, d.newText());
                return;
            }
        }
        // The span this diff was recorded against no longer exists (e.g. a history deletion rebased from a later point);
        // nothing to apply it to
    }
}