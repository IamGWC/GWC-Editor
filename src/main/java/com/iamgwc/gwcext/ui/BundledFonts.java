package com.iamgwc.gwcext.ui;

import javafx.scene.text.Font;

import java.io.IOException;
import java.io.InputStream;

// Registers the bundled Nerd Font with JavaFX and reports the family name JavaFX actually knows it by
public final class BundledFonts {

    private static final String[] FILES = {
            "CaskaydiaCoveNerdFontMono-Regular.ttf",
            "CaskaydiaCoveNerdFontMono-Bold.ttf"
    };
    private static final String FALLBACK_FAMILY = "CaskaydiaCove NFM";

    private static String family = FALLBACK_FAMILY;

    private BundledFonts() {
    }

    // Call once on the JavaFX thread before building any UI
    public static void load() {
        for (String file : FILES) {
            try (InputStream in = BundledFonts.class.getResourceAsStream("fonts/" + file)) {
                if (in == null) {
                    System.err.println("Bundled font missing: " + file);
                    continue;
                }
                Font font = Font.loadFont(in, 12);
                if (font != null && file.contains("Regular")) {
                    family = font.getFamily();
                }
            } catch (IOException e) {
                System.err.println("Could not load bundled font " + file + ": " + e);
            }
        }
    }

    public static String family() {
        return family;
    }
}