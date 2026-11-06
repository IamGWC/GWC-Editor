# GWC Editor | GWC File Format

A desktop editor for `.gwc` files: documents that remember not just what
they say now, but where every word came from. Paste a paragraph in, and
the file remembers it was pasted, when, and what it originally said, even
after you've typed over half of it. Nothing about that history is visible
in Word, LibreOffice, or any other application that opens the file, it's
a completely normal looking document to everything except this editor.

## Note: See GWC-Spec doc for details regarding how the project started and where it is now, as I didnt post the project on git until now

## Why this exists

Most tools show you *that* something changed. This one is
built around a narrower, specific question: **was this text pasted or
typed, and what did it originally look like?** That distinction matters
in contexts where provenance itself is the point, not just the diff.

## How it works

A `.gwc` file is an OOXML compliant file, the same underlying
structure as `.docx`, so Word and LibreOffice can open it like any other
document. The full paste/edit history rides along inside that same file,
in a separate package part that those applications don't recognize and
therefore never render. The editor's own model
never stores two full copies of your document text to compute a diff:
instead it tracks the text as a sequence of *spans*, each one tagged with
where it came from, and keeps an append only log of every operation
(paste, edit) that produced the current state. Opening a file
elsewhere and reading it back always replays that log from scratch, so
there's exactly one code path responsible for "what does this history
produce," used both live and on reopen. The full design rationale,
including a few earlier approaches that didn't work and why, lives in
[`gwc-spec.md`](gwc-spec.md).

## Features

- Opens and displays normally in Word and LibreOffice; history is invisible to both (font size in GWC Editor and other editors will be different)
- Tracks every paste (with position and, if it overwrote a selection, exactly what it replaced) and every typed change (batched per save, diffed at word granularity so a whole word swap reads as a whole word changed, not a stray inserted fragment)
- Detail view shows the actual before/after line for any edit, with the changed word(s) struck through or added inline, labeled by whether the original text came from a paste
- Selecting any history entry (paste or edit) selects and scrolls to its words in the editor
- History entries say where the words are, e.g. "Text typed from line 14 to 19"; blank space before the first word is ignored, so an edit starts where its words start
- Search and filter the history list by type or content
- Right click a history entry to delete just that entry; your document's text is never touched by a deletion
- Warns before overwriting a file that changed outside the editor (another window, a sync conflict, an external edit)
- F11 for fullscreen; drag the title bar to the screen edges to snap or maximize, same as a native window (drag feature is buggy, will fix in later revision of editor)
- Inline underline in the editor for text that originated from a paste
- Settings (gear icon, top right): choose the interface font and the document font separately from every font installed on the machine, with a live preview; remembered between launches
- Bundled Caskaydia Cove Nerd Font Mono so the look is the same on every machine (fonts removed cause reasons)

## Getting started

### Requirements

- JDK 21+
- Maven 3.9+
- Internet access on first build (Maven needs to resolve JavaFX, RichTextFX, Jackson, java diff utils, and Apache POI)

### Build and run

```bash
mvn clean compile
mvn javafx:run
```

### Running from an IDE instead of `mvn javafx:run`

Point your run configuration at `com.iamgwc.gwcext.ui.Launcher`, not
`com.iamgwc.gwcext.ui.MainApp`. `MainApp` extends `javafx.application.Application`,
and launching a class like that directly via a plain classpath `java`
command fails with
`Error: JavaFX runtime components are missing`, even with the JavaFX jars
right there on the classpath. `Launcher` is a plain `main` that just calls
into `MainApp`, which sidesteps that check entirely. `mvn javafx:run` sets
up the module path correctly on its own and can target `MainApp` directly.

### Tests

```bash
tests removed due to errors and I am lazy so I cut them out till I get time to propertly implement proper tests
```

## Project layout

```
src/main/java/com/iamgwc/gwcext/format/   The .gwc format itself: Op types, Span,
                                          GwcDocument, GwcReader, GwcWriter
                                          
src/main/java/com/iamgwc/gwcext/diff/     DiffEngine: word granularity diffing,
                                           mapped onto provenance spans
                                           
src/main/java/com/iamgwc/gwcext/ui/       The JavaFX app: MainApp, Launcher (see above),
                                          EditorController
                                          
src/main/resources/com/iamgwc/gwcext/ui/  editor.fxml, style.css
```

If you're trying to understand the codebase for the first time, read
`GwcDocument` before anything else. It's the one class that both live
editing and file reopening go through, and its class level comment
explains the the op log as source of
truth, spans as a live cache that everything else in the format package
builds on

## Known limitations

This section exists so nobody has to rediscover these the hard way.

- The font setting changes the main window only. Confirmation pop-ups and the dropdown lists inside combo boxes are separate windows and keep the system font. The line numbers beside the text use RichTextFX's own gutter font, not the document font.

- **The inline paste highlight and the click to highlight in editor
  features both rest on specific RichTextFX API assumptions**
  (`Collection<String>` as the per segment style type; the exact
  signatures of `selectRange`, `getParagraphLength`, and
  `requestFollowCaret`) that haven't been independently verified against
  a real RichTextFX build. If either feature does nothing, this is the
  first place to look.
- **(Note: Window Snapping doesnt work upon testing tried a fix but it didnt work)**
- **No undo/redo.** 
    you can still use ctrl+z or ctrl+shift+z to do similar actions, but this works for
- **No OS file association**, so double clicking a `.gwc` file in a file
  manager won't open it here by default.
- **Deliberate inspection isn't defended against.** Anyone can rename a
  `.gwc` file to `.zip` and look inside with an archive tool, the same as
  they could with a real `.docx`. This project keeps ordinary
  document/text applications from surfacing the history; it was never
  meant to withstand someone going looking for it on purpose (**YET**).

## Contributing

Issues and pull requests are welcome. A few things that will make a
change easier to review:

- If you're touching how an operation affects document state, change
  both the live editing code path (`GwcDocument.recordPaste` /
  `recordSaveIfChanged`) and the replay code path (`GwcDocument.replay`)
  together, and add a test that writes, reads back, and asserts the two
  agree. Nearly every bug this project has had came from those two paths
  quietly disagreeing.
- If you're changing anything about the on disk format, update
  `gwc-spec.md` in the same change, including a version history
  entry explaining what changed and why. The spec is meant to be readable
  on its own, without needing to reconstruct the reasoning from commit
  history.