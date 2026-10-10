package com.a1.notify.api;

import com.a1.notify.api.dto.CreateNotificationRequest;
import com.a1.notify.api.dto.NotificationView;
import com.a1.notify.api.dto.PageResponse;
import com.a1.notify.core.NotificationService;
import com.a1.notify.core.NotificationService.CreateResult;
import com.a1.notify.core.NotificationService.Filter;
import com.a1.notify.domain.Category;
import com.a1.notify.domain.Channel;
import com.a1.notify.domain.NotificationStatus;
import com.a1.notify.security.CurrentProject;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Notifications")
@RestController
@RequestMapping("/notifications")
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @Operation(summary = "Create and dispatch a notification (scope SEND)",
            description = "Validates the request, applies preferences, pins the template version, stores one notification per channel "
                    + "and queues delivery. Returns 202; delivery is asynchronous. In-app notifications are DELIVERED immediately. "
                    + "Replaying the same idempotencyKey returns the original notifications and never sends twice. "
                    + "Category OTP is rejected here (use /otp/send). Omit channels to use the configured defaults for the category.")
    @ApiResponse(responseCode = "202", description = "Accepted. One notification per channel; poll GET /notifications/{id} for status",
            content = @Content(schema = @Schema(implementation = NotificationService.CreateResult.class)))
    @PostMapping
    @PreAuthorize("hasAuthority('SEND')")
    public ResponseEntity<CreateResult> create(@Valid @RequestBody CreateNotificationRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.create(CurrentProject.require().requireProjectId(), request));
    }

    @Operation(summary = "Get one notification and its delivery status (scope READ)",
            description = "Never includes recipient contact data or template variables. SENT = accepted by the provider.")
    @ApiResponse(responseCode = "200", description = "The notification",
            content = @Content(schema = @Schema(implementation = NotificationView.class)))
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('READ')")
    public NotificationView get(@PathVariable UUID id) {
        return service.get(CurrentProject.require().requireProjectId(), id);
    }

    @Operation(summary = "Search notification history (scope READ)",
            description = "Paginated (size max 100), newest first, filterable by status, channel, type, category, recipientRef and a created-at range.")
    @GetMapping
    @PreAuthorize("hasAuthority('READ')")
    public PageResponse<NotificationView> search(
            @RequestParam(required = false) NotificationStatus status,
            @RequestParam(required = false) Channel channel,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Category category,
            @RequestParam(required = false) String recipientRef,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.search(CurrentProject.require().requireProjectId(),
                new Filter(status, channel, type, category, recipientRef, from, to), page, size);
    }

    @Operation(summary = "Retry a failed notification (scope RETRY)",
            description = "Only FAILED notifications whose failure is retryable (retries exhausted). Permanent failures return 409 NOT_RETRYABLE.")
    @ApiResponse(responseCode = "202", description = "Re-queued",
            content = @Content(schema = @Schema(implementation = NotificationView.class)))
    @PostMapping("/{id}/retry")
    @PreAuthorize("hasAuthority('RETRY')")
    public ResponseEntity<NotificationView> retry(@PathVariable UUID id) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.retry(CurrentProject.require().requireProjectId(), id));
    }
}
