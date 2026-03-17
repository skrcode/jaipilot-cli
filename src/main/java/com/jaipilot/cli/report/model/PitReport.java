package com.jaipilot.cli.report.model;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public record PitReport(
        int totalMutations,
        int detectedMutations,
        Map<String, Integer> statusCounts,
        List<PitMutation> mutations,
        List<PitMutation> actionableMutations
) {
    public double mutationScore() {
        if (totalMutations == 0) {
            return 0.0d;
        }
        return (detectedMutations * 100.0d) / totalMutations;
    }

    public int statusCount(String status) {
        return statusCounts.getOrDefault(status, 0);
    }
}
