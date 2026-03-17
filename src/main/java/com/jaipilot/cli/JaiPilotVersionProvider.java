package com.jaipilot.cli;

import picocli.CommandLine.IVersionProvider;

public final class JaiPilotVersionProvider implements IVersionProvider {

    @Override
    public String[] getVersion() {
        Package commandPackage = JaiPilotCli.class.getPackage();
        String version = commandPackage == null ? null : commandPackage.getImplementationVersion();
        if (version == null || version.isBlank()) {
            version = "0.1.0-SNAPSHOT";
        }
        return new String[] {version};
    }
}
