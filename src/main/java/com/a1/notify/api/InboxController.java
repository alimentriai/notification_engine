package com.a1.notify.api;

import com.a1.notify.api.dto.NotificationView;
import com.a1.notify.api.dto.PageResponse;
import com.a1.notify.core.DeviceTokenService;
import com.a1.notify.core.InboxService;
import com.a1.notify.security.CurrentProject;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Inbox")
@RestController
public class InboxController {

    public record DeviceRequest(@NotBlank @Size(max = 128) String userRef, @NotBlank @Size(max = 16) String platform,
                                @NotBlank @Size(max = 512) String token) {
    }

    private final InboxService inbox;
    private final DeviceTokenService devices;

    public InboxController(InboxService inbox, DeviceTokenService devices) {
        this.inbox = inbox;
        this.devices = devices;
    }

    @Operation(summary = "List a user's in-app notifications (scope INBOX)",
            description = "recipientRef is the project's own user reference, supplied by the calling backend on behalf of its end user.")
    @GetMapping("/inbox")
    @PreAuthorize("hasAuthority('INBOX')")
    public PageResponse<NotificationView> list(@RequestParam String recipientRef,
                                               @RequestParam(defaultValue = "false") boolean unreadOnly,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        return inbox.list(CurrentProject.require().requireProjectId(), recipientRef, unreadOnly, page, size);
    }

    @Operation(summary = "Mark an in-app notification as read (scope INBOX)")
    @PatchMapping("/inbox/{id}/read")
    @PreAuthorize("hasAuthority('INBOX')")
    public NotificationView markRead(@PathVariable UUID id, @RequestParam String recipientRef) {
        return inbox.markRead(CurrentProject.require().requireProjectId(), recipientRef, id);
    }

    @Operation(summary = "Register a push device token (scope INBOX)",
            description = "Stored for later. Push delivery stays disabled until a PushProvider (FCM/APNs) is configured.")
    @ApiResponse(responseCode = "204", description = "Registered")
    @PostMapping("/devices")
    @PreAuthorize("hasAuthority('INBOX')")
    public ResponseEntity<Void> register(@Valid @RequestBody DeviceRequest request) {
        devices.register(CurrentProject.require().requireProjectId(), request.userRef(), request.platform(), request.token());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Unregister a push device token (scope INBOX)")
    @ApiResponse(responseCode = "204", description = "Unregistered")
    @DeleteMapping("/devices")
    @PreAuthorize("hasAuthority('INBOX')")
    public ResponseEntity<Void> unregister(@RequestParam String token) {
        devices.unregister(CurrentProject.require().requireProjectId(), token);
        return ResponseEntity.noContent().build();
    }
}
