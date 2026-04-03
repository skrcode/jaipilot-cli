package com.jaipilot.cli.classpath;

final class ClassNameParser {

    private ClassNameParser() {
    }

    static String normalizeFqcn(String fqcnOrImport) {
        if (fqcnOrImport == null || fqcnOrImport.isBlank()) {
            throw new IllegalArgumentException("Class name or import must not be blank");
        }

        String value = fqcnOrImport.trim();
        if (value.startsWith("import ")) {
            value = value.substring("import ".length()).trim();
        }
        if (value.endsWith(";")) {
            value = value.substring(0, value.length() - 1).trim();
        }
        if (value.startsWith("static ")) {
            value = value.substring("static ".length()).trim();
            if (!value.endsWith(".*")) {
                int lastDot = value.lastIndexOf('.');
                if (lastDot > 0) {
                    value = value.substring(0, lastDot);
                }
            }
        }
        if (value.endsWith(".class")) {
            value = value.substring(0, value.length() - ".class".length());
        }
        if (value.endsWith(".*")) {
            throw new IllegalArgumentException("Wildcard imports cannot be resolved to a single class: " + fqcnOrImport);
        }
        if (value.contains("/")) {
            value = value.replace('/', '.');
        }

        return value;
    }

    static String classEntryPath(String fqcn) {
        return fqcn.replace('.', '/') + ".class";
    }

    static String sourceEntryPath(String fqcn) {
        String normalized = normalizeFqcn(fqcn);
        int firstDollar = normalized.indexOf('$');
        if (firstDollar >= 0) {
            normalized = normalized.substring(0, firstDollar);
        }
        return normalized.replace('.', '/') + ".java";
    }
}
