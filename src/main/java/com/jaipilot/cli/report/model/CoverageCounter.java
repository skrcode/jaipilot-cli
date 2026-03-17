package com.jaipilot.cli.report.model;

public record CoverageCounter(
        CoverageMetric metric,
        long missed,
        long covered
) {
    public static CoverageCounter add(CoverageCounter left, CoverageCounter right) {
        return new CoverageCounter(left.metric(), left.missed() + right.missed(), left.covered() + right.covered());
    }

    public long total() {
        return missed + covered;
    }

    public double percentage() {
        long total = total();
        if (total == 0L) {
            return 100.0d;
        }
        return (covered * 100.0d) / total;
    }
}
