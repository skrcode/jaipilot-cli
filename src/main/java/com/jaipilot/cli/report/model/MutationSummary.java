package com.jaipilot.cli.report.model;

import java.util.Map;

public record MutationSummary(
        int totalMutations,
        int detectedMutations,
        double mutationScore,
        Map<String, Integer> statusCounts
) {
}
