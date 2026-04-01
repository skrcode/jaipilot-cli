package com.jaipilot.cli.util;

import com.github.javaparser.ParseProblemException;
import com.github.javaparser.StaticJavaParser;

public final class JavaSourceFormatter {

    private JavaSourceFormatter() {
    }

    public static String format(String sourceCode) {
        if (sourceCode == null || sourceCode.isBlank()) {
            return normalizeLineEndings(sourceCode);
        }

        try {
            String formatted = StaticJavaParser.parse(sourceCode).toString();
            if (!formatted.endsWith("\n")) {
                formatted += "\n";
            }
            return formatted;
        } catch (ParseProblemException exception) {
            return normalizeLineEndings(sourceCode);
        }
    }

    private static String normalizeLineEndings(String sourceCode) {
        if (sourceCode == null) {
            return null;
        }
        String normalized = sourceCode.replace("\r\n", "\n").replace('\r', '\n');
        return normalized.endsWith("\n") ? normalized : normalized + "\n";
    }
}
