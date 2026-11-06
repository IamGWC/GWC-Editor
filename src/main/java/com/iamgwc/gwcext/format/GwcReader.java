package com.iamgwc.gwcext.format;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.openxml4j.opc.PackagePartName;
import org.apache.poi.openxml4j.opc.PackagingURIHelper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// Reads a GwcDocument back from a .gwc file. Degrades gracefully: missing history, corrupt lines, or a non-OOXML file never block getting at the text
public final class GwcReader {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());
    private static final TypeReference<Map<String, List<int[]>>> ANCHORS_TYPE = new TypeReference<>() {
    };

    // Compared explicitly against a file's declared gwcFormat, rather than relying only on Jackson's forgiving defaults
    private static final int CURRENT_FORMAT_VERSION = GwcWriter.CURRENT_FORMAT_VERSION;

    private GwcReader() {
    }

    public record ReadResult(GwcDocument document, List<String> warnings) {
    }

    public static ReadResult read(Path gwcFile) throws IOException {
        List<String> warnings = new ArrayList<>();

        try (InputStream in = Files.newInputStream(gwcFile);
             XWPFDocument document = new XWPFDocument(in)) {

            String content = readParagraphs(document);
            String historyRaw = readHistoryPart(document);

            if (historyRaw == null) {
                // As of now saving outside Gwc Editor still preserves the history, but incase this changes, this will fail gracefully
                // Rather than not allow the file to be viewed
                GwcDocument doc = GwcDocument.createNew();
                if (!content.isEmpty()) {
                    doc.recordSaveIfChanged(content);
                }
                warnings.add("No history part found in this document; opened as fresh history "
                        + "(expected if this .gwc file was created or last saved by Word/LibreOffice).");
                return new ReadResult(doc, warnings);
            }

            return parseHistoryAndReconcile(content, historyRaw, warnings);

        } catch (IOException | RuntimeException e) {
            // Not a valid OOXML package, likely a renamed plain text file. Fall back to its raw bytes.
            String content;
            try {
                content = Files.readString(gwcFile, StandardCharsets.UTF_8);
            } catch (IOException readFailure) {
                warnings.add("This file could not be read as a GWC document or as plain text.");
                return new ReadResult(GwcDocument.createNew(), warnings);
            }
            GwcDocument doc = GwcDocument.createNew();
            if (!content.isEmpty()) {
                doc.recordSaveIfChanged(content);
            }
            warnings.add("This file is not a valid GWC document; opened its raw text as a fresh document.");
            return new ReadResult(doc, warnings);
        }
    }

    private static String readParagraphs(XWPFDocument document) {
        List<String> lines = new ArrayList<>();
        for (XWPFParagraph paragraph : document.getParagraphs()) {
            lines.add(paragraph.getText());
        }
        return String.join("\n", lines);
    }

    private static String readHistoryPart(XWPFDocument document) throws IOException {
        try {
            OPCPackage pkg = document.getPackage();
            PackagePartName partName = PackagingURIHelper.createPartName("/gwc/history.jsonl");
            if (!pkg.containPart(partName)) {
                return null;
            }
            PackagePart part = pkg.getPart(partName);
            try (InputStream in = part.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (InvalidFormatException e) {
            throw new IOException("Could not read the history part", e);
        }
    }

    private static ReadResult parseHistoryAndReconcile(String content, String historyRaw, List<String> warnings) {
        List<String> historyLines = historyRaw.lines().toList();
        if (historyLines.isEmpty()) {
            GwcDocument doc = GwcDocument.createNew();
            if (!content.isEmpty()) {
                doc.recordSaveIfChanged(content);
            }
            warnings.add("History part was empty; opened as fresh history.");
            return new ReadResult(doc, warnings);
        }

        String fileId = FileIds.newFileId();
        int firstOpLine = 0;
        try {
            GwcWriter.HistoryHeader header = MAPPER.readValue(historyLines.get(0), GwcWriter.HistoryHeader.class);
            fileId = header.fileId();
            firstOpLine = 1;

            if (header.gwcFormat() > CURRENT_FORMAT_VERSION) {
                warnings.add("This file was created by a newer version of GWC Editor (history format "
                        + header.gwcFormat() + "); some data may not be read correctly.");
            } else if (header.gwcFormat() < CURRENT_FORMAT_VERSION) {
                warnings.add("This file uses an older history format (format " + header.gwcFormat()
                        + "); reading with best-effort compatibility.");
            }
        } catch (Exception e) {
            warnings.add("History header could not be read; a new file id was generated.");
        }

        List<Op> ops = new ArrayList<>();
        Map<String, List<int[]>> savedAnchors = Map.of();
        for (int i = firstOpLine; i < historyLines.size(); i++) {
            String line = historyLines.get(i);
            if (line.isBlank()) {
                continue;
            }
            try {
                if (line.startsWith("{\"anchors\"")) {
                    savedAnchors = MAPPER.convertValue(MAPPER.readTree(line).get("anchors"), ANCHORS_TYPE);
                    continue;
                }
                ops.add(MAPPER.readValue(line, Op.class));
            } catch (Exception e) {
                warnings.add("History entry on line " + (i + 1) + " could not be read and was skipped.");
            }
        }

        GwcDocument doc;
        try {
            doc = GwcDocument.replay(fileId, ops);
        } catch (RuntimeException e) {
            warnings.add("History could not be replayed; opened the document text with fresh history.");
            GwcDocument fresh = GwcDocument.createNew();
            if (!content.isEmpty()) {
                fresh.recordSaveIfChanged(content);
            }
            return new ReadResult(fresh, warnings);
        }

        doc.restoreAnchors(savedAnchors);

        // Document body is ground truth; if replay disagrees, something touched the file outside this reader
        String replayedText = doc.currentText();
        if (!replayedText.equals(content)) {
            warnings.add("Document content did not match replayed history "
                    + "(edited elsewhere, or a history entry was deleted); reconciled as an untracked edit.");
            doc.textChanged(replayedText, content);
            doc.recordSaveIfChanged(content);
        }
        doc.fillMissingAnchors();

        return new ReadResult(doc, warnings);
    }
}