package com.iamgwc.gwcext.format;

import java.security.SecureRandom;

// Generates the random id stamped into a document's history header when it's first created
public final class FileIds {

    private FileIds() {
    }

    public static String newFileId() {
        SecureRandom random = new SecureRandom();
        String hex = "0123456789abcdef";
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            sb.append(hex.charAt(random.nextInt(hex.length())));
        }
        return sb.toString();
    }
}