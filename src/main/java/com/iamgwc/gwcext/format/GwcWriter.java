package com.iamgwc.gwcext.format;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.openxml4j.opc.PackagePartName;
import org.apache.poi.openxml4j.opc.PackageRelationship;
import org.apache.poi.openxml4j.opc.PackagingURIHelper;
import org.apache.poi.openxml4j.opc.TargetMode;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

public final class GwcWriter {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private static final String HISTORY_PART_URI = "/gwc/history.jsonl";
    private static final String HISTORY_CONTENT_TYPE = "application/x-gwc-history+ndjson";
    private static final String HISTORY_RELATIONSHIP_TYPE = "http://schemas.gwc-editor.app/2026/relationships/history";

    // Bump when Op's on-disk shape changes; GwcReader compares against its own copy
    public static final int CURRENT_FORMAT_VERSION = 1;

    private GwcWriter() {
    }

    public record HistoryHeader(int gwcFormat, String fileId) {
    }

    public static void write(Path gwcFile, GwcDocument doc) throws IOException {
        try (XWPFDocument document = new XWPFDocument()) {
            writeParagraphs(document, doc.currentText());
            try {
                writeHistoryPart(document, buildHistoryContent(doc));
            } catch (InvalidFormatException e) {
                throw new IOException("Could not attach the history part to the document package", e);
            }
            try (OutputStream out = Files.newOutputStream(
                    gwcFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                document.write(out);
            }
        }
    }

    // One paragraph per line, including empty ones, so GwcReader can rebuild the exact text by joining with "\n"
    private static void writeParagraphs(XWPFDocument document, String text) {
        for (String line : text.split("\n", -1)) {
            XWPFParagraph paragraph = document.createParagraph();
            XWPFRun run = paragraph.createRun();
            run.setText(line);
        }
    }

    private static String buildHistoryContent(GwcDocument doc) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(MAPPER.writeValueAsString(new HistoryHeader(CURRENT_FORMAT_VERSION, doc.fileId())));
        for (Op op : doc.opLog()) {
            sb.append("\n").append(MAPPER.writeValueAsString(op));
        }
        sb.append("\n").append(MAPPER.writeValueAsString(Map.of("anchors", doc.anchorsSnapshot())));
        return sb.toString();
    }

    private static void writeHistoryPart(XWPFDocument document, String historyContent)
            throws IOException, InvalidFormatException {
        OPCPackage pkg = document.getPackage();
        PackagePartName partName = PackagingURIHelper.createPartName(HISTORY_PART_URI);

        // Clear out whatever a previous save left behind before writing the current one
        if (pkg.containPart(partName)) {
            pkg.removePart(partName);
        }
        for (PackageRelationship rel : pkg.getRelationshipsByType(HISTORY_RELATIONSHIP_TYPE)) {
            pkg.removeRelationship(rel.getId());
        }

        PackagePart part = pkg.createPart(partName, HISTORY_CONTENT_TYPE);
        try (OutputStream out = part.getOutputStream()) {
            out.write(historyContent.getBytes(StandardCharsets.UTF_8));
        }
        pkg.addRelationship(partName, TargetMode.INTERNAL, HISTORY_RELATIONSHIP_TYPE);
    }
}