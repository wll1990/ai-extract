package com.aiextract.dto;

public record CompanyAiTestResult(
        boolean ok,
        long latencyMs,
        String errorCode,
        String message
) {
}
