package com.jaipilot.cli.report.model;

public record VerificationIssue(
        String reason,
        String action
) {
}
