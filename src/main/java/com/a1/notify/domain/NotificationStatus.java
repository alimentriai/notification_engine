package com.a1.notify.domain;

/**
 * SENT means the provider accepted the message, not that the recipient received it.
 * DELIVERED is only set for in-app items (stored for the recipient) or by a provider receipt.
 */
public enum NotificationStatus {
    PENDING,
    PROCESSING,
    SENT,
    DELIVERED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == SENT || this == DELIVERED || this == FAILED || this == CANCELLED;
    }

    public boolean canTransitionTo(NotificationStatus to) {
        if (this == to) {
            return true;
        }
        return switch (this) {
            case PENDING -> to == PROCESSING || to == CANCELLED || to == DELIVERED;
            case PROCESSING -> to == SENT || to == FAILED || to == PENDING;
            case SENT -> to == DELIVERED;
            case FAILED -> to == PENDING;
            case DELIVERED, CANCELLED -> false;
        };
    }
}
