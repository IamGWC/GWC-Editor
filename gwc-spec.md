# .gwc File Format Specification (v4.4)

**GWC**  a document format that tracks paste provenance and full edit history, stored as a single file, that opens as a normal document in Word and LibreOffice while keeping the history hidden from them.

## Version history | more explained in following sections

- **v1**: single plain text file, content followed by a sentinel line and inline JSON history. Rejected: any plain text editor shows the trailing history data.
- **v2**: two files (`document.gwc` pure text + `document.gwc.history` companion). Solved the history readability problem on main doc, at the cost of the document being two files.
- **v3**: single file again, but as an arbitrary two entry ZIP archive (`content.txt` + `history.jsonl`), but Word and LibreOffice couldn't open it at all either, since it lacked the mandatory internal parts an OOXML document needs, so they reported it as corrupted.
- **v4**: .gwc file is now a valid OOXML wordprocessing package, Word and LibreOffice open it normally and display the document text. The edit history rides along in the very same package, but as a separate part with a custom content type, linked by a relationship type neither application recognizes. 
- **v4.1**: no on disk schema changes beyond what v4's `paste` op already gained (`replacedLength`, see section 3.1). This revision was about correctness and robustness: automatic checkpointing, an explicit format version comparison on read instead of only forgiving defaults, a  history deletion operation, and defensive handling of Unicode surrogate pairs throughout the diff and span logic.
- **v4.2**: also no on disk schema changes. Two UI facing changes: the history viewer no longer caps itself at showing only the most recent 50 entries (was previously limited to 50 viewable entries), and the history deletion operation from v4.1 became point based rather than all or nothing, a person can now delete a specific entry and everything before it while keeping everything after it untouched.
- **v4.3**: the `checkpoint` op is removed entirely. It existed for two purposes, automatic replay cost bounding and representing a point based history deletion, and both turned out to cause more confusion than they were worth: automatic checkpointing created entries the person never asked for, and because deletion reused the same op type, the detail view and history list couldn't visually distinguish "I deliberately deleted history here" from "the editor silently checkpointed here." There is no automatic checkpointing anymore, period. Deletion now represents the kept starting point as an ordinary `edit` op (a single diff whose `old` is empty and `new` is the full reconstructed text, as if it had all been typed at once), reusing the exact mechanism a document's very first content already goes through, rather than introducing a special case. A file written by an earlier version that still contains `"op":"checkpoint"` lines will have those lines skipped (with a warning) by a v4.3 reader, since `checkpoint` is no longer a recognized op type; the content section is ground truth, so the reader falls back to reconciling against it rather than failing to open the file.

---
- **v4.4 (current version)**: three changes. (1) `paste` gained `atLine`/`endLine`, the lines its words landed on, computed once at paste time (display only; absent on older files, read as 0). (2) Line numbers for pastes and edits ignore whitespace: leading whitespace, including blank lines, before the first word is skipped when deciding where an entry starts, and trailing whitespace is ignored when deciding where it ends, so typing two blank lines then a sentence is reported at the sentence's line, not the blank lines'. Stored `range`/`old`/`new` stay exact so replay is unaffected, except that whitespace identical at the front of both sides of a hunk is kept out of the stored diff. (3) History deletion is now exactly one thing: it removes the chosen entry from the log and nothing else. The v4.2/v4.3 behavior (delete an entry plus everything before it, replaced by a starting `edit`) is gone, as is the code that reconstructed text at a point in history.

## 1. File structure overview

A `.gwc` file is an OOXML package containing:

```
mydocument.gwc 
├── [Content_Types].xml         standard OOXML content type declarations
├── _rels/.rels                  standard package level relationships
├── word/document.xml            the visible document body: one paragraph per line of text
└── gwc/history.jsonl            custom part: header line + one JSON object per operation
```

- The visible document body (`word/document.xml`) contains the current text as ordinary Word paragraphs, one paragraph per line, so the reconstruction back into a single string is simply joining paragraph text with `\n`.
- `gwc/history.jsonl` is declared with a custom content type (`application/x-gwc-history+ndjson`) and linked from the package with a custom relationship type (`http://schemas.gwc-editor.app/2026/relationships/history`).
- Building and reading this package uses Apache POI (`poi` + `poi-ooxml`), which handles the low level OOXML plumbing correctly.

**What this does and doesn't defend against:** a person using a low level OOXML/OPC inspection tool, or simply renaming the file to `.zip` and opening it in an archive manager, can still find and read the `gwc/history.jsonl` part directly, the same way anyone can inspect the internal XML of a `.docx` file.

---

## 2. The history part header

`gwc/history.jsonl`'s first line is a small JSON header, distinct from the operation entries that follow it:

```json
{"gwcFormat":1,"fileId":"4f9a21c8b6d3e0f1"}
```

| field | meaning |
|---|---|
| `gwcFormat` | Format version of the history part itself, so a future version can be introduced without breaking old readers |
| `fileId` | A random 16 character lowercase hex string, generated once when the document is first created |

Every line after the header is one operation (see Section 3).

---

## 3. History part: operation log

operation log format: `paste` and `edit` entries. There is no third op type.

### 3.1 `paste`
```json
{"op":"paste","id":"o7","ts":"2026-09-13T10:02:11Z","at":142,"replacedLength":0,"text":"the quick brown fox jumps over a hedge"}
```

`replacedLength` is the number of characters that were selected and overwritten by this paste, starting at `at`, or `0` for a plain insertion with no selection. This matters for correctness, not just bookkeeping: pasting over a selection must delete the previously selected text from the document model, not merely insert the new text alongside it. An earlier version of this implementation only ever recorded the insertion, on the (incorrect) assumption that the editor widget visually replacing the selection meant the underlying model was in sync too, it wasn't, and the old selected text silently reappeared elsewhere in the document (typically at the end) the next time the file was reopened and history was replayed. A file written before this field existed simply has no `replacedLength` key, which is read as `0`, the correct behavior for every paste recorded before selections were tracked, since they were always plain insertions.

### 3.2 `edit`
```json
{"op":"edit","id":"o8","ts":"2026-09-13T10:06:44Z","diffs":[
  {"span":"o7","range":[4,9],"old":"quick","new":"stupid",
   "oldLineNo":1,"oldLine":"the quick brown fox","oldLineRange":[4,9],
   "newLineNo":1,"newLine":"the stupid brown fox","newLineRange":[4,11]}
]}
```

`oldLine`/`newLine` (and their line numbers and in line ranges) capture the **whole line** the change landed on, before and after, not just a window of surrounding words. An earlier design captured only two words of context read from the pre edit text; that went stale whenever a single save contained more than one change on the same line, because a later change's "before" context was still read from the old text and so still showed an earlier change's old wording, rather than the real resulting sentence. Capturing the true whole line, before and after, directly from the actual old/new text avoids that entirely: there's nothing to reconstruct and therefore nothing to go stale. These fields are display only: replaying history to reconstruct document state uses only `range`, `old`, and `new`. The boundary search for the end of an affected line searches forward from the change's end position, not its start; searching from start truncates the capture to the first line whenever the inserted text itself spans multiple lines (for example, adding a blank line plus a new line of content in one save).

A history deletion (see Section 5) is represented as an ordinary `edit` with a single diff entry: `span` is `"typed"`, `range` is `[0,0]`, `old` is empty, and `new` is the entire reconstructed text at the kept point, exactly as if that text had all been typed into an empty document in one go. No separate op type is needed to represent "this is where the kept history starts."

---

## 4. Save behavior

paste forces an immediate save; typed changes batch into one `edit` op at Ctrl+S/Save click. Every save rewrites the entire package (document body and history part) from scratch. Every operation's timestamp is a full date and time, and the detail view always displays both.

---

## 5. Retention: no display limit, no automatic collapsing, and deliberate deletion

**No display limit (v4.2 change).** Earlier versions capped the history viewer at showing only the most recent 50 entries. That UI level cap is gone; the viewer now shows the entire log. This was never a format level constraint to begin with, `gwc/history.jsonl` has always held the complete history regardless of what any particular viewer chose to display, so this change has no on disk implication at all.

**No automatic collapsing of any kind (v4.3 change).** An earlier version wrote a non destructive snapshot automatically once a document's history grew past a size threshold, purely as a replay cost optimization. That was removed: it created entries nobody asked for, and once the history deletion feature (below) also started producing similarly shaped entries, the two became genuinely hard to tell apart just by glancing at the history list. There is no implicit, size triggered, or time triggered collapsing of history anymore. If replay cost over very long histories becomes a real problem again, that needs a design that doesn't inject entries a person never asked for; it is explicitly left unsolved here rather than half solved.

**History deletion (v4.4: single entry).** A person can delete any one entry from the history. Only that entry is removed from `gwc/history.jsonl`; no other entry is changed, nothing is inserted in its place, and the document body is not touched. Because later entries record positions relative to the text as it stood when they were made, a replay that is missing a middle entry may not reproduce the document exactly. A reader handles this the way it handles any mismatch (Section 7): diff positions that point past the end of the text during replay are clamped rather than treated as errors, the document body is ground truth, and any difference is folded in as one untracked `edit`. The practical effect of a deletion is therefore that history becomes a little less precise, never that text is lost. Any implementation exposing this should still ask for confirmation, since a deleted entry cannot be recovered once the file is saved.

---

## 6. Diff and span provenance

Diffing happens at **word granularity**, not character granularity (v4 change). A character level Myers diff on something like "name" -> "surname" finds the minimal edit, inserting "sur" before the shared suffix "name", which never reports "name" as removed at all. Tokenizing into words first (splitting on runs of word characters, runs of punctuation, and runs of whitespace) means a swap like that is reported as one whole word removed and one whole word added, matching what a person editing prose actually expects to see struck through and added. The diff is still mapped onto provenance spans exactly as before, so an edit can be traced back to the paste or typed baseline it touched.

**Unicode surrogate pairs (v4.1 addition).** All positions in this format are UTF 16 char offsets, consistent with how Java strings, most text editing widgets, and JSON already represent text. The sharp edge: characters outside the Basic Multilingual Plane (most emoji, some rarer scripts) are represented as a *pair* of char values, not one. A diff, line, or span boundary that lands between the two halves of such a pair would corrupt it. Rather than re indexing the whole format by Unicode code point, every place that computes a split point snaps it to a safe boundary, and the word tokenizer defensively re merges any pair the regex might otherwise split. This is a mitigation applied at the boundary computation layer, not a guarantee that every conceivable code path has been exhaustively checked, treat it as a strong reduction in risk, verified by tests using real emoji, not an absolute one.

---

## 7. Reconciling content edited outside the tool

If a history part is present but the document body text doesn't match what replaying it produces (for example, someone had it open in Word, edited a sentence, and saved), the mismatch is folded in as one untracked edit, attributed to the whole document, with a warning surfaced to the user, e.g. "Content did not match history; reconciled as an untracked edit." This guarantees a person's actual words are never lost or overwritten by the tool, at the cost of losing paste/edit provenance for whatever changed outside it.

If the file isn't a valid OOXML package at all (for example, a plain `.txt` file renamed to `.gwc`), the reader falls back to treating the raw file contents as a fresh document with no history yet.

---

## 8. Integrity

- Malformed operation lines are skipped with a warning rather than blocking the whole file from opening.
- A malformed or missing history header falls back to the "no history yet" path.
- A missing or unreadable history part falls back per Section 7 rather than crashing.
- An `edit` diff entry whose `span` no longer matches any live span (its originating `paste` was deleted, see Section 5) is silently skipped during replay rather than erroring, and an entry whose `range` points past the end of its span is clamped to the span. Neither can affect a document whose history has not had an entry deleted. If replay fails outright, a reader keeps the document body and starts fresh history; it must never fall back to treating the file's raw bytes as text.

---