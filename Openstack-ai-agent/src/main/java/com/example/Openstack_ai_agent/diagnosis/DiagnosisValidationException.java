package com.example.Openstack_ai_agent.diagnosis;

public class DiagnosisValidationException extends RuntimeException {
    public DiagnosisValidationException(String message) {
        super(message);
    }

    public DiagnosisValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
