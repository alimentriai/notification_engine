package com.a1.notify.api;

import com.a1.notify.core.ProjectAdminService;
import com.a1.notify.core.ProjectAdminService.IssuedKey;
import com.a1.notify.domain.ProjectEntity;
import com.a1.notify.queue.DlqService;
import com.a1.notify.web.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Platform administration (X-Api-Key = bootstrap admin key). Project keys can never reach these routes. */
@Tag(name = "Admin")
@RestController
@RequestMapping("/admin")
@PreAuthorize("hasAuthority('ADMIN')")
public class AdminController {

    public record CreateProjectRequest(@Schema(description = "Unique lowercase project code", example = "sharique-erp") @NotBlank @Size(max = 64) @Pattern(regexp = "[a-z0-9][a-z0-9-]*") String code,
                                       @Schema(example = "Sharique ERP") @NotBlank @Size(max = 200) String name,
                                       @Schema(description = "Verified SES sender for this project", example = "no-reply@example.com") @Size(max = 320) String defaultSenderEmail,
                                       @Schema(description = "Optional SES configuration set") @Size(max = 128) String sesConfigSet,
                                       @Schema(description = "India DLT sender id (header)", example = "A1CRSH") @Size(max = 16) String smsSenderId,
                                       @Schema(description = "India DLT principal entity id") @Size(max = 64) String smsEntityId,
                                       @Schema(description = "Requests per minute, default from engine config", example = "120") @Min(1) Integer rateLimitPerMin,
                                       @Schema(description = "Notifications per rolling 24h, default from engine config", example = "50000") @Min(1) Integer dailyQuota) {
    }

    public record IssueKeyRequest(@Schema(description = "Where the key is used", example = "erp-backend-prod") @NotBlank @Size(max = 100) String label,
                                  @Schema(description = "SEND, READ, RETRY, TEMPLATES, PREFERENCES, OTP, INBOX. Defaults to SEND and READ", example = "[\"SEND\", \"READ\"]") Set<String> scopes) {
    }

    public record ProjectView(Long id, String code, String name, String status, String defaultSenderEmail,
                              int rateLimitPerMin, int dailyQuota) {
        static ProjectView of(ProjectEntity p) {
            return new ProjectView(p.getId(), p.getCode(), p.getName(), p.getStatus(), p.getDefaultSenderEmail(),
                    p.getRateLimitPerMin(), p.getDailyQuota());
        }
    }

    private final ProjectAdminService admin;
    private final ObjectProvider<DlqService> dlq;

    public AdminController(ProjectAdminService admin, ObjectProvider<DlqService> dlq) {
        this.admin = admin;
        this.dlq = dlq;
    }

    @Operation(summary = "Create a project (admin key)")
    @ApiResponse(responseCode = "201", description = "Created",
            content = @Content(schema = @Schema(implementation = ProjectView.class)))
    @PostMapping("/projects")
    public ResponseEntity<ProjectView> createProject(@Valid @RequestBody CreateProjectRequest r) {
        ProjectEntity p = admin.createProject(r.code(), r.name(), r.defaultSenderEmail(), r.sesConfigSet(), r.smsSenderId(),
                r.smsEntityId(), r.rateLimitPerMin(), r.dailyQuota());
        return ResponseEntity.status(HttpStatus.CREATED).body(ProjectView.of(p));
    }

    @Operation(summary = "List projects (admin key)")
    @GetMapping("/projects")
    public List<ProjectView> projects() {
        return admin.listProjects().stream().map(ProjectView::of).toList();
    }

    @Operation(summary = "Activate or suspend a project (admin key)",
            description = "A suspended project's keys stop working immediately.")
    @ApiResponse(responseCode = "204", description = "Updated")
    @PatchMapping("/projects/{code}/status")
    public ResponseEntity<Void> status(@PathVariable String code, @RequestParam boolean active) {
        admin.setStatus(code, active);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Issue a project API key (admin key)",
            description = "The raw key (ntf_...) is returned ONCE and stored only as a SHA-256 hash. Scopes: SEND, READ, RETRY, TEMPLATES, PREFERENCES, OTP, INBOX.")
    @ApiResponse(responseCode = "201", description = "Created. The key is shown only in this response",
            content = @Content(schema = @Schema(implementation = IssuedKey.class)))
    @PostMapping("/projects/{code}/keys")
    public ResponseEntity<IssuedKey> issueKey(@PathVariable String code, @Valid @RequestBody IssueKeyRequest r) {
        return ResponseEntity.status(HttpStatus.CREATED).body(admin.issueKey(code, r.label(), r.scopes()));
    }

    @Operation(summary = "List a project's API keys (admin key)",
            description = "Metadata only: id, prefix (first characters of the key), label, scopes, active, created and last used. "
                    + "The raw key is never retrievable; if it was lost, issue a new key and revoke the old one.")
    @GetMapping("/projects/{code}/keys")
    public List<ProjectAdminService.KeyView> listKeys(@PathVariable String code) {
        return admin.listKeys(code);
    }

    @Operation(summary = "Revoke a project API key (admin key)")
    @ApiResponse(responseCode = "204", description = "Revoked")
    @DeleteMapping("/projects/{code}/keys/{keyId}")
    public ResponseEntity<Void> revoke(@PathVariable String code, @PathVariable Long keyId) {
        admin.revokeKey(code, keyId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Inspect the dead-letter queue (admin key)",
            description = "Returns the approximate size and up to 10 messages (ids only). Requires the SQS queue to be enabled.")
    @GetMapping("/dlq")
    public Map<String, Object> dlqPeek(@RequestParam(defaultValue = "10") @Min(1) @Max(10) int max) {
        DlqService service = requireDlq();
        return Map.of("approximateSize", service.approximateSize(), "messages", service.peek(max));
    }

    @Operation(summary = "Redrive dead-lettered messages (admin key)",
            description = "Resets each notification to PENDING, requeues it and removes it from the DLQ.")
    @PostMapping("/dlq/redrive")
    public Map<String, Integer> dlqRedrive(@RequestParam(defaultValue = "100") @Min(1) @Max(1000) int max) {
        return Map.of("redriven", requireDlq().redrive(max));
    }

    private DlqService requireDlq() {
        DlqService service = dlq.getIfAvailable();
        if (service == null) {
            throw new ApiException(HttpStatus.CONFLICT, "QUEUE_DISABLED", "notify.queue.enabled is false");
        }
        return service;
    }
}
