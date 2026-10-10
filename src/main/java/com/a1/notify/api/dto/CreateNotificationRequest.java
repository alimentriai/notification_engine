package com.a1.notify.api.dto;

import com.a1.notify.domain.Category;
import com.a1.notify.domain.Channel;
import com.a1.notify.domain.Priority;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Schema(description = "Request to create a notification. One notification is created per channel.")
public record CreateNotificationRequest(
        @Schema(description = "Free-form event name for reporting and filtering", example = "WEIGHMENT_APPROVAL_REQUESTED")
        @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9_.:-]+", message = "may contain letters, digits and _ . : -") String type,
        @Schema(description = "Category. MARKETING needs explicit user consent; OTP is rejected here (use /otp/send); OTP/VERIFICATION/SYSTEM ignore opt-outs", example = "APPROVAL")
        @NotNull Category category,
        @Schema(description = "Channels to use. Omit to use the engine's configured defaults for the category", example = "[\"IN_APP\", \"EMAIL\"]")
        List<Channel> channels,
        @Schema(description = "Code of a template the project created for every requested channel", example = "approval-requested")
        @NotBlank @Size(max = 100) String templateCode,
        @Schema(description = "Template locale, falls back to en", example = "en")
        @Size(max = 16) String locale,
        @Schema(description = "Who receives it. ref is required for IN_APP and PUSH; email for EMAIL; phone (E.164) for SMS")
        @NotNull @Valid Recipient recipient,
        @Schema(description = "Values for the template's {{placeholders}} (strings or numbers). Do not put secrets here; they are stored until the contact-data retention wipe", type = "object", additionalProperties = Schema.AdditionalPropertiesValue.TRUE, example = "{\"name\": \"Ops\", \"id\": 101}")
        Map<String, Object> variables,
        @Schema(description = "Priority, defaults to NORMAL", example = "HIGH")
        Priority priority,
        @Schema(description = "Stable key per business event (for example <entity>-<id>-<event>). A replay returns the original notifications and never sends twice", example = "weighment-101-approval-requested")
        @Size(max = 100) String idempotencyKey,
        @Schema(description = "Do not send before this time (ISO-8601)", example = "2026-12-01T09:00:00Z")
        OffsetDateTime scheduledAt,
        @Schema(description = "Drop the notification if still unsent after this time (ISO-8601)", example = "2026-12-31T00:00:00Z")
        OffsetDateTime expiresAt) {

    @Schema(description = "Recipient identifiers. Contact data is wiped from the engine after the retention period and is never returned by the API")
    public record Recipient(
            @Schema(description = "The calling project's own user id", example = "user-42") @Size(max = 128) String ref,
            @Schema(description = "Required for EMAIL", example = "ops@example.com") @Size(max = 320) String email,
            @Schema(description = "Required for SMS, E.164 (Indian mobiles: +91 and 10 digits starting 6-9)", example = "+919876543210") @Size(max = 32) String phone) {
    }
}
