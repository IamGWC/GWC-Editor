package com.iamgwc.gwcext.diff;

import com.github.difflib.DiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Patch;
import com.iamgwc.gwcext.format.CodePoints;
import com.iamgwc.gwcext.format.Op;
import com.iamgwc.gwcext.format.Span;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Diffs the last-saved text against the live text and maps changes onto spans so provenance survives the edit
 * Diffing is word granular (not character level), so "name" -> "surname" reads as one word swapped, not a stray insert
 * Each DiffEntry carries the whole before/after line, not just nearby words, so a second change on the same line
 * in one save can never show a stale version of the first (an earlier word context design had exactly that bug)
 */
public final class DiffEngine {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("\\w+|[^\\w\\s]+|\\s+");

    private DiffEngine() {
    }

    public record Result(List<Op.DiffEntry> entries, List<int[]> newRanges) {
    }

    private record Located(Op.DiffEntry entry, int[] newRange) {
    }

    public static Result diffAndApply(String oldText, String newText, List<Span> spans) {
        List<String> oldTokens = tokenize(oldText);
        List<String> newTokens = tokenize(newText);
        int[] oldTokenStarts = cumulativeStarts(oldTokens);
        int[] newTokenStarts = cumulativeStarts(newTokens);

        Patch<String> patch = DiffUtils.diff(oldTokens, newTokens);
        List<Located> located = new ArrayList<>();

        int[] spanStarts = new int[spans.size()];
        int running = 0;
        for (int i = 0; i < spans.size(); i++) {
            spanStarts[i] = running;
            running += spans.get(i).length();
        }

        // Apply deltas in descending position order: a save with 2+ hunks in the same span (common
        // right after a history deletion collapses everything to one span) would otherwise have an
        // earlier hunk's mutation shift the text underneath a later hunk's already-computed offset, corrupting it.
        List<AbstractDelta<String>> deltas = new ArrayList<>(patch.getDeltas());
        deltas.sort((a, b) -> Integer.compare(b.getSource().getPosition(), a.getSource().getPosition()));

        for (AbstractDelta<String> delta : deltas) {
            int oldTokenIndex = delta.getSource().getPosition();
            int oldTokenCount = delta.getSource().getLines().size();
            int oldStart = oldTokenStarts[oldTokenIndex];
            int oldEnd = oldTokenCount == 0
                    ? oldStart
                    : oldTokenStarts[oldTokenIndex + oldTokenCount - 1]
                        + oldTokens.get(oldTokenIndex + oldTokenCount - 1).length();

            int newTokenIndex = delta.getTarget().getPosition();
            int newTokenCount = delta.getTarget().getLines().size();
            int newStart = newTokenStarts[newTokenIndex];
            int newEnd = newTokenCount == 0
                    ? newStart
                    : newTokenStarts[newTokenIndex + newTokenCount - 1]
                        + newTokens.get(newTokenIndex + newTokenCount - 1).length();

            String oldSub = oldText.substring(oldStart, oldEnd);
            String newSub = newText.substring(newStart, newEnd);

            // Whitespace identical at the front of both sides didn't change; keep it out of the stored diff
            int commonLeadingWhitespace = 0;
            while (commonLeadingWhitespace < oldSub.length() && commonLeadingWhitespace < newSub.length()
                    && Character.isWhitespace(oldSub.charAt(commonLeadingWhitespace))
                    && oldSub.charAt(commonLeadingWhitespace) == newSub.charAt(commonLeadingWhitespace)) {
                commonLeadingWhitespace++;
            }
            if (commonLeadingWhitespace > 0) {
                oldStart += commonLeadingWhitespace;
                newStart += commonLeadingWhitespace;
                oldSub = oldSub.substring(commonLeadingWhitespace);
                newSub = newSub.substring(commonLeadingWhitespace);
            }

            // Display positions start at the first word; range/old/new above stay exact for replay
            LineInfo oldLine = lineContaining(oldText, skipWhitespace(oldText, oldStart, oldEnd), oldEnd);
            LineInfo newLine = lineContaining(newText, skipWhitespace(newText, newStart, newEnd), newEnd);

            Op.DiffEntry entry = splitAcrossSpans(oldStart, oldEnd, oldSub, newSub, oldLine, newLine, spans, spanStarts);
            located.add(new Located(entry, new int[]{skipWhitespace(newText, newStart, newEnd), newEnd}));
        }

        // Application order above is about correctness, not readability; restore natural reading order for the UI.
        located.sort(Comparator
                .comparingInt((Located l) -> l.entry().oldLineNumber())
                .thenComparingInt(l -> l.entry().oldLineRange()[0]));

        List<Op.DiffEntry> entries = new ArrayList<>();
        List<int[]> newRanges = new ArrayList<>();
        for (Located l : located) {
            entries.add(l.entry());
            newRanges.add(l.newRange());
        }
        return new Result(entries, newRanges);
    }

    // A change spanning two spans is attributed entirely to the one it starts in; the tail is just cleared from the rest
    private static Op.DiffEntry splitAcrossSpans(
            int oldStart, int oldEnd, String oldSub, String newSub,
            LineInfo oldLine, LineInfo newLine,
            List<Span> spans, int[] spanStarts) {

        int spanIndex = 0;
        for (int i = spans.size() - 1; i >= 0; i--) {
            if (spanStarts[i] <= oldStart) {
                spanIndex = i;
                break;
            }
        }

        Span span = spans.get(spanIndex);
        int localStart = oldStart - spanStarts[spanIndex];
        int spanRemaining = span.length() - localStart;

        if (oldEnd - oldStart <= spanRemaining) {
            int localEnd = localStart + (oldEnd - oldStart);
            Op.DiffEntry entry = makeDiffEntry(span.originId(), localStart, localEnd, oldSub, newSub, oldLine, newLine);
            span.replaceRange(localStart, localEnd, newSub);
            return entry;
        } else {
            int localEnd = span.length();
            Op.DiffEntry entry = makeDiffEntry(span.originId(), localStart, localEnd, oldSub, newSub, oldLine, newLine);
            span.replaceRange(localStart, localEnd, newSub);

            int consumedFromOld = spanRemaining;
            int spanCursor = spanIndex + 1;
            while (consumedFromOld < (oldEnd - oldStart) && spanCursor < spans.size()) {
                Span next = spans.get(spanCursor);
                int take = Math.min(next.length(), (oldEnd - oldStart) - consumedFromOld);
                next.replaceRange(0, take, "");
                consumedFromOld += take;
                spanCursor++;
            }
            return entry;
        }
    }

    private static Op.DiffEntry makeDiffEntry(
            String spanId, int localStart, int localEnd, String oldSub, String newSub,
            LineInfo oldLine, LineInfo newLine) {
        return new Op.DiffEntry(
                spanId, new int[]{localStart, localEnd}, oldSub, newSub,
                oldLine.lineNumber, oldLine.text, oldLine.relativeRange,
                newLine.lineNumber, newLine.text, newLine.relativeRange);
    }

    private static int skipWhitespace(String text, int start, int end) {
        int i = start;
        while (i < end && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }

    private record LineInfo(int lineNumber, String text, int[] relativeRange) {
    }

    // Search for the closing newline from end, not start, or a change that inserts multiple lines gets truncated to just the first one
    private static LineInfo lineContaining(String fullText, int start, int end) {
        int lineStart = fullText.lastIndexOf('\n', Math.max(0, start - 1)) + 1;
        int lineEnd = fullText.indexOf('\n', end);
        if (lineEnd == -1) {
            lineEnd = fullText.length();
        }
        int lineNumber = 1;
        for (int i = 0; i < lineStart; i++) {
            if (fullText.charAt(i) == '\n') {
                lineNumber++;
            }
        }
        String lineText = fullText.substring(lineStart, lineEnd);
        int[] relativeRange = CodePoints.snapRange(fullText, start, Math.min(end, lineEnd));
        relativeRange[0] -= lineStart;
        relativeRange[1] -= lineStart;
        return new LineInfo(lineNumber, lineText, relativeRange);
    }

    private static List<String> tokenize(String text) {
        List<String> rawTokens = new ArrayList<>();
        Matcher m = TOKEN_PATTERN.matcher(text);
        while (m.find()) {
            rawTokens.add(m.group());
        }
        return mergeSplitSurrogatePairs(rawTokens);
    }

    // Re-merges a supplementary character (emoji etc.) if the regex above ever split it across two tokens
    private static List<String> mergeSplitSurrogatePairs(List<String> rawTokens) {
        List<String> merged = new ArrayList<>(rawTokens.size());
        for (String token : rawTokens) {
            if (!merged.isEmpty()) {
                String prev = merged.get(merged.size() - 1);
                if (!prev.isEmpty() && !token.isEmpty()
                        && Character.isHighSurrogate(prev.charAt(prev.length() - 1))
                        && Character.isLowSurrogate(token.charAt(0))) {
                    merged.set(merged.size() - 1, prev + token);
                    continue;
                }
            }
            merged.add(token);
        }
        return merged;
    }

    private static int[] cumulativeStarts(List<String> tokens) {
        int[] starts = new int[tokens.size() + 1];
        int running = 0;
        for (int i = 0; i < tokens.size(); i++) {
            starts[i] = running;
            running += tokens.get(i).length();
        }
        starts[tokens.size()] = running;
        return starts;
    }
}