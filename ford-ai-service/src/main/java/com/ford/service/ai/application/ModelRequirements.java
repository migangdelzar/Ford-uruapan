package com.ford.service.ai.application;

public record ModelRequirements(boolean toolCalling, boolean structuredOutput,
                                boolean imageInput, boolean audioInput) {
    public ModelRequirements(boolean toolCalling, boolean structuredOutput) {
        this(toolCalling, structuredOutput, false, false);
    }
}
