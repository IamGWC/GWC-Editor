package com.iamgwc.gwcext.ui;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

// Font settings dialog: pick the interface font and the document font from everything installed
public final class SettingsDialog {

    private static final String SAMPLE = "The quick brown fox jumps over the lazy dog 0123456789";

    private SettingsDialog() {
    }

    // Returns true if the person confirmed; the new choices are already saved to settings by then
    public static boolean show(Window owner, AppSettings settings, List<String> stylesheets) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Settings");
        dialog.setHeaderText(null);
        dialog.initStyle(StageStyle.UNDECORATED);

        List<String> families = new ArrayList<>(Font.getFamilies());
        for (String needed : List.of(settings.uiFont(), settings.documentFont(), BundledFonts.family())) {
            if (!families.contains(needed)) {
                families.add(0, needed);
            }
        }

        ComboBox<String> uiPicker = picker(families, settings.uiFont());
        ComboBox<String> documentPicker = picker(families, settings.documentFont());

        Button reset = new Button("Reset to defaults");
        reset.getStyleClass().add("toolbar-button");
        reset.setOnAction(e -> {
            uiPicker.setValue(BundledFonts.family());
            documentPicker.setValue(BundledFonts.family());
        });

        VBox content = new VBox(8);
        content.setPadding(new Insets(16));
        content.setPrefWidth(460);
        content.getChildren().addAll(
                section("Interface font", "Toolbar, history list and detail panel", uiPicker),
                section("Document font", "The text you are editing", documentPicker),
                reset);

        DialogPane pane = dialog.getDialogPane();
        pane.setContent(content);
        pane.getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        pane.getStylesheets().addAll(stylesheets);
        pane.getStyleClass().add("app-shell");
        pane.setStyle(AppSettings.fontStyle(settings.uiFont()));
        pane.lookupButton(ButtonType.OK).getStyleClass().addAll("toolbar-button", "primary");
        pane.lookupButton(ButtonType.CANCEL).getStyleClass().add("toolbar-button");

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) {
            return false;
        }
        if (uiPicker.getValue() != null) {
            settings.setUiFont(uiPicker.getValue());
        }
        if (documentPicker.getValue() != null) {
            settings.setDocumentFont(documentPicker.getValue());
        }
        return true;
    }

    private static ComboBox<String> picker(List<String> families, String current) {
        ComboBox<String> box = new ComboBox<>(FXCollections.observableArrayList(families));
        box.setValue(current);
        box.setVisibleRowCount(12);
        box.setMaxWidth(Double.MAX_VALUE);
        box.getStyleClass().add("history-filter");
        return box;
    }

    private static VBox section(String title, String subtitle, ComboBox<String> picker) {
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("panel-title");
        Label subtitleLabel = new Label(subtitle);
        subtitleLabel.getStyleClass().add("panel-subtitle");

        Label preview = new Label(SAMPLE);
        preview.getStyleClass().add("settings-preview");
        preview.setWrapText(true);
        preview.setMaxWidth(Double.MAX_VALUE);
        preview.setStyle(AppSettings.fontStyle(picker.getValue()));
        picker.valueProperty().addListener((obs, oldV, newV) -> {
            if (newV != null) {
                preview.setStyle(AppSettings.fontStyle(newV));
            }
        });

        VBox box = new VBox(6, titleLabel, subtitleLabel, picker, preview);
        box.setPadding(new Insets(0, 0, 10, 0));
        return box;
    }
}