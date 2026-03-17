package com.jaipilot.cli;

import com.jaipilot.cli.commands.VerifyCommand;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

@Command(
        name = "jaipilot",
        mixinStandardHelpOptions = true,
        versionProvider = JaiPilotVersionProvider.class,
        description = "Runs JAIPilot verification workflows against Maven projects.",
        subcommands = {
            VerifyCommand.class
        }
)
public final class JaiPilotCli implements Callable<Integer> {

    @Spec
    private CommandSpec spec;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new JaiPilotCli()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() {
        spec.commandLine().usage(spec.commandLine().getOut());
        return CommandLine.ExitCode.OK;
    }
}
