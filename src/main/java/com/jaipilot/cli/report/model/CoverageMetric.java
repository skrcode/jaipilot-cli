package com.jaipilot.cli.report.model;

public enum CoverageMetric {
    LINE("LINE"),
    BRANCH("BRANCH"),
    INSTRUCTION("INSTRUCTION");

    private final String label;

    CoverageMetric(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
