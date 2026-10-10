package com.a1.notify.api;

import com.a1.notify.domain.Channel;
import com.a1.notify.domain.TemplateEntity;
import com.a1.notify.security.CurrentProject;
import com.a1.notify.template.TemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Templates are per project and versioned; creating an existing code/channel/locale adds a new version. */
@Tag(name = "Templates")
@RestController
@RequestMapping("/templates")
public class TemplateController {

    public record CreateTemplateRequest(@Schema(example = "approval-requested") @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String code,
                                        @Schema(example = "EMAIL") @NotNull Channel channel,
                                        @Schema(description = "Defaults to en", example = "en") @Size(max = 16) String locale,
                                        @Schema(description = "Email subject / in-app title", example = "Approval needed for weighment #{{id}}") @Size(max = 300) String subject,
                                        @Schema(example = "<p>Hi {{name}}, weighment <b>#{{id}}</b> needs approval.</p>") @Size(max = 20000) String bodyHtml,
                                        @Schema(example = "Hi {{name}}, weighment #{{id}} needs approval.") @Size(max = 5000) String bodyText,
                                        @Schema(description = "India DLT content template id (SMS)") @Size(max = 64) String dltTemplateId) {
    }

    public record TemplateView(Long id, String code, Channel channel, String locale, int version, String subject,
                               String bodyHtml, String bodyText, String dltTemplateId, boolean active) {
        static TemplateView of(TemplateEntity t) {
            return new TemplateView(t.getId(), t.getCode(), t.getChannel(), t.getLocale(), t.getVersion(), t.getSubject(),
                    t.getBodyHtml(), t.getBodyText(), t.getDltTemplateId(), t.isActive());
        }
    }

    private final TemplateService templates;

    public TemplateController(TemplateService templates) {
        this.templates = templates;
    }

    @Operation(summary = "Create a template version (scope TEMPLATES)",
            description = "Creating an existing code/channel/locale adds a new version; the latest active version is used for new notifications and each notification pins the version it was accepted with. "
                    + "{{variable}} placeholders are required at send time; HTML bodies escape values. EMAIL needs a subject plus html or text; SMS/PUSH/IN_APP need bodyText (SMS may carry a dltTemplateId).")
    @ApiResponse(responseCode = "201", description = "Created",
            content = @Content(schema = @Schema(implementation = TemplateView.class)))
    @PostMapping
    @PreAuthorize("hasAuthority('TEMPLATES')")
    public ResponseEntity<TemplateView> create(@Valid @RequestBody CreateTemplateRequest r) {
        TemplateEntity t = templates.create(CurrentProject.require().requireProjectId(), r.code(), r.channel(), r.locale(),
                r.subject(), r.bodyHtml(), r.bodyText(), r.dltTemplateId());
        return ResponseEntity.status(HttpStatus.CREATED).body(TemplateView.of(t));
    }

    @Operation(summary = "List the project's templates (scope TEMPLATES)")
    @GetMapping
    @PreAuthorize("hasAuthority('TEMPLATES')")
    public List<TemplateView> list() {
        return templates.list(CurrentProject.require().requireProjectId()).stream().map(TemplateView::of).toList();
    }

    @Operation(summary = "Activate or deactivate a template version (scope TEMPLATES)")
    @ApiResponse(responseCode = "204", description = "Updated")
    @PatchMapping("/{id}/active")
    @PreAuthorize("hasAuthority('TEMPLATES')")
    public ResponseEntity<Void> setActive(@PathVariable Long id, @RequestParam boolean value) {
        templates.setActive(CurrentProject.require().requireProjectId(), id, value);
        return ResponseEntity.noContent().build();
    }
}
