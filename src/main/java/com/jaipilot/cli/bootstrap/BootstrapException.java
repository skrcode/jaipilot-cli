package com.jaipilot.cli.bootstrap;

import com.jaipilot.cli.report.model.VerificationIssue;
import java.nio.file.Path;

public final class BootstrapException extends RuntimeException {

    private final VerificationIssue issue;
    private final Path workspacePath;

    public BootstrapException(VerificationIssue issue) {
        this(issue, null);
    }

    public BootstrapException(VerificationIssue issue, Path workspacePath) {
        super(issue.reason());
        this.issue = issue;
        this.workspacePath = workspacePath;
    }

    public VerificationIssue issue() {
        return issue;
    }

    public Path workspacePath() {
        return workspacePath;
    }
}
