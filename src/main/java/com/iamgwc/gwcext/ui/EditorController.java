package com.iamgwc.gwcext.ui;

import com.iamgwc.gwcext.format.GwcDocument;
import com.iamgwc.gwcext.format.GwcReader;
import com.iamgwc.gwcext.format.GwcWriter;
import com.iamgwc.gwcext.format.Op;
import com.iamgwc.gwcext.format.Span;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

// Owns the document being edited: text area, history list, detail view, and disk I/O
public class EditorController {

    @FXML private StackPane editorHost;
    @FXML private ListView<Op> historyList;
    @FXML private TextFlow detailFlow;
    @FXML private Label statusLabel;
    @FXML private TextField searchField;
    @FXML private ComboBox<String> typeFilter;

    private final AppSettings settings = new AppSettings();

    private CodeArea codeArea;
    private String liveText = "";
    private boolean loadingText;
    private GwcDocument document;
    private Path currentPath;
    private Stage stage;

    // Backing list for the history panel; filteredHistory is what the ListView actually shows
    private final ObservableList<Op> allHistoryOps = FXCollections.observableArrayList();
    private FilteredList<Op> filteredHistory;

    // On disk modified time to detect an external change before overwriting
    private FileTime lastKnownModifiedTime;

    private static final DateTimeFormatter DATE_TIME_FMT =
            DateTimeFormatter.ofPattern("MMM d, yyyy 'at' HH:mm:ss").withZone(ZoneId.systemDefault());

    @FXML
    public void initialize() {
        document = GwcDocument.createNew();

        codeArea = new CodeArea();
        codeArea.setParagraphGraphicFactory(LineNumberFactory.get(codeArea));
        codeArea.setWrapText(true);
        codeArea.getStyleClass().add("code-area");
        editorHost.getChildren().add(codeArea);
        codeArea.textProperty().addListener((obs, oldText, newText) -> onTextChanged(oldText, newText));

        historyList.setCellFactory(lv -> new HistoryCell());

        filteredHistory = new FilteredList<>(allHistoryOps, op -> true);
        historyList.setItems(filteredHistory);

        typeFilter.setItems(FXCollections.observableArrayList("All", "Paste", "Edit"));
        typeFilter.setValue("All");
        typeFilter.valueProperty().addListener((obs, oldV, newV) -> applyHistoryFilter());
        searchField.textProperty().addListener((obs, oldV, newV) -> applyHistoryFilter());

        setupPasteDetection();
        setupSaveShortcut();
        historyList.getSelectionModel().selectedItemProperty()
                .addListener((obs, oldOp, newOp) -> showDetail(newOp));
        refreshHistoryView();
        setStatus("New untitled document");
    }

    public void attachStage(Stage stage) {
        this.stage = stage;
        applyFonts();
    }

    // The interface font goes on the scene root so everything inherits it; the document font goes only on the editor
    private void applyFonts() {
        stage.getScene().getRoot().setStyle(AppSettings.fontStyle(settings.uiFont()));
        codeArea.setStyle(AppSettings.fontStyle(settings.documentFont()));
    }

    @FXML
    public void onOpenSettings(ActionEvent event) {
        if (SettingsDialog.show(stage, settings, stage.getScene().getStylesheets())) {
            applyFonts();
            setStatus("Fonts updated");
        }
    }

    // Search and filtering

    private void applyHistoryFilter() {
        String query = searchField.getText() == null ? "" : searchField.getText().toLowerCase();
        String type = typeFilter.getValue();

        filteredHistory.setPredicate(op -> {
            boolean typeMatches = type == null || type.equals("All")
                    || (type.equals("Paste") && op instanceof Op.Paste)
                    || (type.equals("Edit") && op instanceof Op.Edit);
            if (!typeMatches) {
                return false;
            }
            return query.isEmpty() || opSearchText(op).toLowerCase().contains(query);
        });
    }

    private String opSearchText(Op op) {
        return switch (op) {
            case Op.Paste p -> p.text();
            case Op.Edit e -> {
                StringBuilder sb = new StringBuilder();
                for (Op.DiffEntry d : e.diffs()) {
                    sb.append(d.oldLine()).append(' ').append(d.newLine()).append(' ');
                }
                yield sb.toString();
            }
        };
    }

    // Paste detection and typing
    // RichTextFX has no dedicated "paste happened" event, so Ctrl+V is intercepted directly instead
    private void setupPasteDetection() {
        codeArea.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.V && event.isShortcutDown()) {
                handlePaste();
                event.consume();
            }
        });
    }

    private void handlePaste() {
        Clipboard clipboard = Clipboard.getSystemClipboard();
        if (!clipboard.hasString()) {
            return;
        }
        String pasted = clipboard.getString();
        if (pasted == null || pasted.isEmpty()) {
            return;
        }

        int caret = codeArea.getCaretPosition();
        int selStart = codeArea.getSelection().getStart();
        int selEnd = codeArea.getSelection().getEnd();

        if (selEnd > selStart) {
            codeArea.replaceText(selStart, selEnd, pasted);
        } else {
            codeArea.insertText(caret, pasted);
        }

        int insertAt = selEnd > selStart ? selStart : caret;
        int replacedLength = selEnd > selStart ? (selEnd - selStart) : 0;
        Op.Paste op = document.recordPaste(insertAt, replacedLength, pasted);

        // A paste saves immediately; it doesn't wait for Ctrl+S
        persistToDisk();
        refreshHistoryView();
        refreshProvenanceHighlighting();
        setStatus("Pasted " + pasted.length() + " characters at " + insertAt
                + " (saved " + DATE_TIME_FMT.format(op.ts()) + ")");
    }

    private void setupSaveShortcut() {
        codeArea.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.S && event.isShortcutDown()) {
                onSave(null);
                event.consume();
            }
        });
    }

    @FXML
    public void onSave(ActionEvent event) {
        var edit = document.recordSaveIfChanged(codeArea.getText());
        persistToDisk();
        refreshHistoryView();
        refreshProvenanceHighlighting();
        if (edit.isPresent()) {
            setStatus("Saved " + edit.get().diffs().size() + " change(s) at "
                    + DATE_TIME_FMT.format(edit.get().ts()));
        } else {
            setStatus("Saved (no changes since last save)");
        }
    }

    // File operations

    @FXML
    public void onNew(ActionEvent event) {
        document = GwcDocument.createNew();
        currentPath = null;
        lastKnownModifiedTime = null;
        loadingText = true;
        codeArea.clear();
        loadingText = false;
        refreshHistoryView();
        setStatus("New untitled document");
    }

    @FXML
    public void onOpen(ActionEvent event) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open .gwc file");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("GWC files", "*.gwc"));
        File chosen = chooser.showOpenDialog(stage);
        if (chosen == null) {
            return;
        }
        Path path = chosen.toPath();
        try {
            GwcReader.ReadResult result = GwcReader.read(path);
            document = result.document();
            currentPath = path;
            try {
                lastKnownModifiedTime = Files.getLastModifiedTime(path);
            } catch (IOException e) {
                lastKnownModifiedTime = null;
            }
            loadingText = true;
            codeArea.replaceText(document.currentText());
            loadingText = false;
            refreshHistoryView();
            refreshProvenanceHighlighting();
            String warningSuffix = result.warnings().isEmpty()
                    ? ""
                    : " (" + result.warnings().size() + " warning(s), see console)";
            result.warnings().forEach(System.err::println);
            setStatus("Opened " + path.getFileName() + warningSuffix);
        } catch (IOException e) {
            showError("Could not open file", e.getMessage());
        }
    }

    private void persistToDisk() {
        Path target = currentPath;
        if (target == null) {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Save .gwc file");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("GWC files", "*.gwc"));
            File chosen = chooser.showSaveDialog(stage);
            if (chosen == null) {
                return;
            }
            target = chosen.toPath();
            currentPath = target;
        } else if (!confirmOverwriteIfExternallyModified(target)) {
            setStatus("Save cancelled: file was modified elsewhere");
            return;
        }
        try {
            GwcWriter.write(target, document);
            try {
                lastKnownModifiedTime = Files.getLastModifiedTime(target);
            } catch (IOException e) {
                lastKnownModifiedTime = null;
            }
        } catch (IOException e) {
            showError("Could not save file", e.getMessage());
        }
    }

    // Warns rather than silently overwriting if the file changed outside this editor since we last touched it
    private boolean confirmOverwriteIfExternallyModified(Path target) {
        if (lastKnownModifiedTime == null || !Files.exists(target)) {
            return true;
        }
        try {
            FileTime actual = Files.getLastModifiedTime(target);
            if (!actual.equals(lastKnownModifiedTime)) {
                Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                alert.setTitle("File changed elsewhere");
                alert.setHeaderText(null);
                alert.setContentText("This file has been modified outside GWC Editor since it was "
                        + "opened or last saved here (another window, a sync conflict, or an "
                        + "external edit). Overwrite it anyway?");
                return alert.showAndWait().filter(bt -> bt == ButtonType.OK).isPresent();
            }
        } catch (IOException e) {
            // Can't check right now; let the save through rather than block it
        }
        return true;
    }

    // Deleting a history entry
    private void onDeleteHistoryEntry(Op op) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Delete this history entry");
        confirm.setHeaderText(null);
        confirm.setContentText("This permanently removes this entry from the history list. "
                + "It does not change your document's text in any way. This cannot be undone. Continue?");
        if (confirm.showAndWait().filter(bt -> bt == ButtonType.OK).isEmpty()) {
            return;
        }
        document.deleteHistoryEntry(op.id());
        persistToDisk();
        refreshHistoryView();
        setStatus("Deleted history entry from " + DATE_TIME_FMT.format(op.ts()));
    }

    // Underlines pasted text in the editor, assumes CodeArea's segment style type is Collection<String>
    private void refreshProvenanceHighlighting() {
        List<Span> spans = document.spans();
        if (spans.isEmpty()) {
            return;
        }
        StyleSpansBuilder<Collection<String>> builder = new StyleSpansBuilder<>();
        for (Span s : spans) {
            if (s.length() == 0) {
                continue;
            }
            Collection<String> styleClasses = s.originType() == Span.Origin.PASTE
                    ? List.of("pasted-text")
                    : Collections.emptyList();
            builder.add(styleClasses, s.length());
        }
        try {
            codeArea.setStyleSpans(0, builder.create());
        } catch (Exception ignored) {
            // editor text and document.spans() briefly disagreed in length; skip this pass
        }
    }

    // History list and jump to edit

    private void refreshHistoryView() {
        allHistoryOps.setAll(document.opLog());
        int lastIndex = historyList.getItems().size() - 1;
        if (lastIndex >= 0) {
            historyList.getSelectionModel().select(lastIndex);
            historyList.scrollTo(lastIndex);
        } else {
            detailFlow.getChildren().clear();
        }
    }

    private void onTextChanged(String oldText, String newText) {
        liveText = newText;
        if (loadingText) {
            return;
        }
        document.textChanged(oldText, newText);
        historyList.refresh();
        Op selected = historyList.getSelectionModel().getSelectedItem();
        if (selected != null) {
            detailFlow.getChildren().setAll(buildDetailNodes(selected));
        }
    }

    private void showDetail(Op op) {
        detailFlow.getChildren().clear();
        if (op == null) {
            return;
        }
        detailFlow.getChildren().addAll(buildDetailNodes(op));
        jumpToAndHighlight(op);
    }

    // Selects the words an entry touched in the editor and scrolls to them. Failures are logged, not swallowed
    private void jumpToAndHighlight(Op op) {
        int[] extent = currentExtent(op);
        if (extent == null) {
            return;
        }
        try {
            int docLength = codeArea.getLength();
            int start = Math.max(0, Math.min(extent[2], docLength));
            int end = Math.max(start, Math.min(extent[3], docLength));

            codeArea.moveTo(start);
            codeArea.requestFollowCaret();
            if (end > start) {
                codeArea.selectRange(start, end);
            }
        } catch (Exception e) {
            System.err.println("jumpToAndHighlight failed: " + e);
        }
    }

    private List<Node> buildDetailNodes(Op op) {
        String time = DATE_TIME_FMT.format(op.ts());
        List<Node> nodes = new ArrayList<>();

        switch (op) {
            case Op.Paste p -> {
                nodes.add(heading("Pasted " + location(p) + " \u00b7 " + time));
                String meta = p.replacedLength() > 0
                        ? "Replaced " + p.replacedLength() + " character(s) at position " + p.at()
                            + " \u00b7 " + p.text().length() + " character(s) pasted\n\n"
                        : "Inserted at position " + p.at() + " \u00b7 "
                            + p.text().length() + " character(s)\n\n";
                nodes.add(meta(meta));
                nodes.add(plain(p.text()));
            }
            case Op.Edit e -> {
                nodes.add(heading("Edited " + location(e) + " \u00b7 " + time));
                nodes.addAll(buildEditLineBlocks(e));
            }
        }
        return nodes;
    }

    private static String lineRangeText(int startLine, int endLine) {
        return startLine == endLine ? ("at line " + startLine) : ("from line " + startLine + " to " + endLine);
    }

    private String location(Op op) {
        int[] extent = currentExtent(op);
        return extent == null ? "" : lineRangeText(extent[0], extent[1]);
    }

    private int[] currentExtent(Op op) {
        List<int[]> ranges = document.anchorsOf(op.id());
        if (ranges.isEmpty()) {
            return null;
        }
        int firstLine = Integer.MAX_VALUE;
        int lastLine = Integer.MIN_VALUE;
        int startOffset = Integer.MAX_VALUE;
        int endOffset = Integer.MIN_VALUE;
        for (int[] range : ranges) {
            int[] words = wordSpan(liveText, range[0], range[1]);
            firstLine = Math.min(firstLine, lineAt(liveText, words[0]));
            lastLine = Math.max(lastLine, lineAt(liveText, words[1]));
            startOffset = Math.min(startOffset, words[0]);
            endOffset = Math.max(endOffset, words[1]);
        }
        return new int[]{firstLine, lastLine, startOffset, endOffset};
    }

    private static int[] wordSpan(String text, int start, int end) {
        int from = Math.max(0, Math.min(start, text.length()));
        int to = Math.max(from, Math.min(end, text.length()));
        int first = from;
        while (first < to && Character.isWhitespace(text.charAt(first))) {
            first++;
        }
        int last = to;
        while (last > first && Character.isWhitespace(text.charAt(last - 1))) {
            last--;
        }
        if (first == last) {
            return new int[]{from, from};
        }
        return new int[]{first, last};
    }

    private static int lineAt(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    // Groups an edit's diffs by line so a line touched twice in one save shows once, not repeated
    private List<Node> buildEditLineBlocks(Op.Edit e) {
        List<Node> nodes = new ArrayList<>();
        List<int[]> anchors = document.anchorsOf(e.id());

        LinkedHashMap<String, List<Integer>> groups = new LinkedHashMap<>();
        for (int i = 0; i < e.diffs().size(); i++) {
            Op.DiffEntry d = e.diffs().get(i);
            groups.computeIfAbsent(d.oldLineNumber() + "->" + d.newLineNumber(), k -> new ArrayList<>()).add(i);
        }

        for (List<Integer> indexes : groups.values()) {
            List<Op.DiffEntry> group = new ArrayList<>();
            for (int i : indexes) {
                group.add(e.diffs().get(i));
            }
            Op.DiffEntry first = group.get(0);

            int newLine = first.newLineNumber();
            int newEndLine = first.newEndLineNumber();
            int firstIndex = indexes.get(0);
            if (firstIndex < anchors.size()) {
                int[] words = wordSpan(liveText, anchors.get(firstIndex)[0], anchors.get(firstIndex)[1]);
                newLine = lineAt(liveText, words[0]);
                newEndLine = lineAt(liveText, words[1]);
            }
            int oldLine = Math.max(1, first.oldLineNumber() + (newLine - first.newLineNumber()));

            boolean fromPaste = isSpanOriginallyPasted(first.span());
            String oldLabel = (fromPaste ? "Text pasted" : "Text typed") + " - at line " + oldLine;
            String newLabel = "Text typed - " + lineRangeText(newLine, newEndLine);

            nodes.add(meta("\n" + oldLabel + "\n"));
            nodes.addAll(renderLineWithHighlights(first.oldLine(), group, true));
            nodes.add(plain("\n"));

            nodes.add(meta("\n" + newLabel + "\n"));
            nodes.addAll(renderLineWithHighlights(first.newLine(), group, false));
            nodes.add(plain("\n"));
        }

        return nodes;
    }

    private List<Node> renderLineWithHighlights(String line, List<Op.DiffEntry> group, boolean isOldSide) {
        List<Node> nodes = new ArrayList<>();

        List<int[]> ranges = new ArrayList<>();
        for (Op.DiffEntry d : group) {
            ranges.add(isOldSide ? d.oldLineRange() : d.newLineRange());
        }
        ranges.sort((a, b) -> Integer.compare(a[0], b[0]));

        int cursor = 0;
        for (int[] r : ranges) {
            int start = Math.max(cursor, Math.min(r[0], line.length()));
            int end = Math.max(start, Math.min(r[1], line.length()));
            if (start > cursor) {
                nodes.add(plain(line.substring(cursor, start)));
            }
            if (end > start) {
                nodes.add(isOldSide ? removed(line.substring(start, end)) : added(line.substring(start, end)));
            }
            cursor = Math.max(cursor, end);
        }
        if (cursor < line.length()) {
            nodes.add(plain(line.substring(cursor)));
        }
        if (line.isEmpty()) {
            nodes.add(plain(""));
        }
        return nodes;
    }

    private boolean isSpanOriginallyPasted(String spanId) {
        if (spanId.equals(Span.TYPED_ORIGIN)) {
            return false;
        }
        return document.opLog().stream().anyMatch(op -> op instanceof Op.Paste p && p.id().equals(spanId));
    }

    private Text heading(String s) {
        Text t = new Text(s + "\n");
        t.getStyleClass().add("detail-heading");
        return t;
    }

    private Text meta(String s) {
        Text t = new Text(s);
        t.getStyleClass().add("detail-meta");
        return t;
    }

    private Text plain(String s) {
        Text t = new Text(s);
        t.getStyleClass().add("detail-plain");
        return t;
    }

    private Text removed(String s) {
        Text t = new Text(s);
        t.getStyleClass().add("detail-removed");
        return t;
    }

    private Text added(String s) {
        Text t = new Text(s);
        t.getStyleClass().add("detail-added");
        return t;
    }

    // Colored dot + summary per history row, with a right-click delete menu | Non-static to call back into the controller
    private final class HistoryCell extends ListCell<Op> {
        private final DateTimeFormatter timeFormat =
                DateTimeFormatter.ofPattern("MMM d, HH:mm:ss").withZone(ZoneId.systemDefault());

        @Override
        protected void updateItem(Op op, boolean empty) {
            super.updateItem(op, empty);
            if (empty || op == null) {
                setGraphic(null);
                setText(null);
                setContextMenu(null);
                return;
            }

            Circle dot = new Circle(4);
            dot.getStyleClass().add(switch (op) {
                case Op.Paste p -> "dot-paste";
                case Op.Edit e -> "dot-edit";
            });

            String time = timeFormat.format(op.ts());
            String summary = switch (op) {
                case Op.Paste p -> "Text pasted " + location(p);
                case Op.Edit e -> "Text typed " + location(e);
            };

            Label timeLabel = new Label(time);
            timeLabel.getStyleClass().add("history-cell-time");
            Label summaryLabel = new Label(summary);
            summaryLabel.getStyleClass().add("history-cell-summary");

            VBox textBox = new VBox(timeLabel, summaryLabel);
            textBox.setSpacing(1);

            HBox row = new HBox(dot, textBox);
            row.setSpacing(10);
            row.setAlignment(Pos.CENTER_LEFT);

            MenuItem deleteItem = new MenuItem("Delete this entry");
            deleteItem.setOnAction(e -> onDeleteHistoryEntry(op));
            setContextMenu(new ContextMenu(deleteItem));

            setGraphic(row);
            setText(null);
        }
    }

    private void setStatus(String text) {
        statusLabel.setText(text);
    }

    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }
}