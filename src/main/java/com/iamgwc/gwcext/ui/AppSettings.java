package com.iamgwc.gwcext.ui;

import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

// Per-user settings, kept between launches with java.util.prefs
public final class AppSettings {

    private static final String UI_FONT_KEY = "uiFont";
    private static final String DOCUMENT_FONT_KEY = "documentFont";

    private final Preferences prefs;

    public AppSettings() {
        this(Preferences.userNodeForPackage(AppSettings.class));
    }

    AppSettings(Preferences prefs) {
        this.prefs = prefs;
    }

    // Font for the whole interface: toolbar, history, detail panel
    public String uiFont() {
        return prefs.get(UI_FONT_KEY, BundledFonts.family());
    }

    // Font for the text being edited
    public String documentFont() {
        return prefs.get(DOCUMENT_FONT_KEY, BundledFonts.family());
    }

    public void setUiFont(String family) {
        prefs.put(UI_FONT_KEY, family);
        flush();
    }

    public void setDocumentFont(String family) {
        prefs.put(DOCUMENT_FONT_KEY, family);
        flush();
    }

    public void resetFonts() {
        prefs.remove(UI_FONT_KEY);
        prefs.remove(DOCUMENT_FONT_KEY);
        flush();
    }

    // Inline CSS that sets only the font family; quote and brace characters are stripped so a stored value can't escape the string
    public static String fontStyle(String family) {
        String safe = family.replaceAll("['\"\\\\;{}]", "");
        return "-fx-font-family: '" + safe + "';";
    }

    private void flush() {
        try {
            prefs.flush();
        } catch (BackingStoreException e) {
            System.err.println("Could not save settings: " + e);
        }
    }
}