package com.jaipilot.cli.report.model;

import java.nio.file.Path;

public record CheckResult(
        String key,
        boolean passed,
        double actual,
        double threshold
) {
}
