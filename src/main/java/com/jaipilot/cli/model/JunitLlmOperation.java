package com.jaipilot.cli.model;

public enum JunitLlmOperation {
    GENERATE("generate");

    private final String apiValue;

    JunitLlmOperation(String apiValue) {
        this.apiValue = apiValue;
    }

    public String apiValue() {
        return apiValue;
    }
}
