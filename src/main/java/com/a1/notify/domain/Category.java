package com.a1.notify.domain;

/**
 * Notification categories. Mandatory categories ignore user opt-outs; opt-in categories
 * are suppressed unless the user has explicitly consented.
 */
public enum Category {
    OTP(true, false),
    VERIFICATION(true, false),
    APPROVAL(false, false),
    SUCCESS(false, false),
    ERROR(false, false),
    WARNING(false, false),
    INFO(false, false),
    REMINDER(false, false),
    SYSTEM(true, false),
    MARKETING(false, true);

    private final boolean mandatory;
    private final boolean optIn;

    Category(boolean mandatory, boolean optIn) {
        this.mandatory = mandatory;
        this.optIn = optIn;
    }

    public boolean isMandatory() {
        return mandatory;
    }

    public boolean isOptIn() {
        return optIn;
    }
}
