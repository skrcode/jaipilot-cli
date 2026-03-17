package com.jaipilot.cli.util;

import java.util.Locale;

public final class PercentFormatter {

    private PercentFormatter() {
    }

    public static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
