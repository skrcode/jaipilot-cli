package com.jaipilot.cli.process;

import java.nio.file.Path;
import java.util.List;

public interface LocalBuildCommandBuilder {

    BuildTool buildTool();

    List<String> buildTestCompile(
            Path projectRoot,
            Path explicitBuildExecutable,
            List<String> additionalArguments
    );

    List<String> buildSingleTestExecution(
            Path projectRoot,
            Path explicitBuildExecutable,
            List<String> additionalArguments,
            String testSelector
    );

    List<String> buildCodebaseRulesValidation(
            Path projectRoot,
            Path explicitBuildExecutable,
            List<String> additionalArguments
    );
}
