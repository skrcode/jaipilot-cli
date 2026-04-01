package com.jaipilot.cli.update;

import java.util.regex.Pattern;

final class VersionComparator {

    private static final Pattern NUMERIC_VERSION = Pattern.compile("^[0-9]+(?:\\.[0-9]+)*$");

    private VersionComparator() {
    }

    static int compare(String left, String right) {
        String normalizedLeft = normalize(left);
        String normalizedRight = normalize(right);
        if (NUMERIC_VERSION.matcher(normalizedLeft).matches() && NUMERIC_VERSION.matcher(normalizedRight).matches()) {
            String[] leftParts = normalizedLeft.split("\\.");
            String[] rightParts = normalizedRight.split("\\.");
            int length = Math.max(leftParts.length, rightParts.length);
            for (int index = 0; index < length; index++) {
                int leftValue = index < leftParts.length ? Integer.parseInt(leftParts[index]) : 0;
                int rightValue = index < rightParts.length ? Integer.parseInt(rightParts[index]) : 0;
                int comparison = Integer.compare(leftValue, rightValue);
                if (comparison != 0) {
                    return comparison;
                }
            }
            return 0;
        }
        return normalizedLeft.compareTo(normalizedRight);
    }

    static String normalize(String version) {
        if (version == null) {
            throw new IllegalArgumentException("Version must not be null.");
        }
        String normalized = version.trim();
        if (normalized.startsWith("v") || normalized.startsWith("V")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Version must not be blank.");
        }
        return normalized;
    }
}
