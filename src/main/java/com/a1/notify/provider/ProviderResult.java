package com.a1.notify.provider;

/** Outcome of one provider call. Codes are sanitized identifiers, never raw provider payloads. */
public record ProviderResult(Outcome outcome, String providerMessageId, String errorCode) {

    public enum Outcome {
        SENT,
        TRANSIENT_FAILURE,
        PERMANENT_FAILURE
    }

    public static ProviderResult sent(String providerMessageId) {
        return new ProviderResult(Outcome.SENT, providerMessageId, null);
    }

    public static ProviderResult transientFailure(String errorCode) {
        return new ProviderResult(Outcome.TRANSIENT_FAILURE, null, errorCode);
    }

    public static ProviderResult permanentFailure(String errorCode) {
        return new ProviderResult(Outcome.PERMANENT_FAILURE, null, errorCode);
    }

    public boolean isSent() {
        return outcome == Outcome.SENT;
    }
}
