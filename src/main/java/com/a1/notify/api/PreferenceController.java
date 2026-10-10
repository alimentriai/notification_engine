package com.a1.notify.api;

import com.a1.notify.core.PreferenceService;
import com.a1.notify.domain.Category;
import com.a1.notify.domain.Channel;
import com.a1.notify.domain.PreferenceEntity;
import com.a1.notify.security.CurrentProject;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Preferences")
@RestController
public class PreferenceController {

    public record PreferenceItem(@NotNull Channel channel, @NotNull Category category, boolean enabled) {
    }

    public record UpdateRequest(@NotBlank @Size(max = 128) String userRef, @NotEmpty List<@Valid PreferenceItem> preferences) {
    }

    public record PreferenceView(Channel channel, Category category, boolean enabled, OffsetDateTime consentAt) {
    }

    private final PreferenceService service;

    public PreferenceController(PreferenceService service) {
        this.service = service;
    }

    @Operation(summary = "Get a user's preferences (scope PREFERENCES)")
    @GetMapping("/notification-preferences")
    @PreAuthorize("hasAuthority('PREFERENCES')")
    public List<PreferenceView> get(@RequestParam String userRef) {
        return service.list(CurrentProject.require().requireProjectId(), userRef).stream()
                .map((PreferenceEntity p) -> new PreferenceView(p.getChannel(), p.getCategory(), p.isEnabled(), p.getConsentAt()))
                .toList();
    }

    @Operation(summary = "Replace/update a user's preferences (scope PREFERENCES)",
            description = "OTP, VERIFICATION and SYSTEM are mandatory and cannot be disabled (400 MANDATORY_CATEGORY). MARKETING is opt-in: it is cancelled unless enabled here.")
    @PutMapping("/notification-preferences")
    @PreAuthorize("hasAuthority('PREFERENCES')")
    public List<PreferenceView> update(@Valid @RequestBody UpdateRequest request) {
        Long projectId = CurrentProject.require().requireProjectId();
        request.preferences().forEach(p -> service.upsert(projectId, request.userRef(), p.channel(), p.category(), p.enabled()));
        return get(request.userRef());
    }
}
