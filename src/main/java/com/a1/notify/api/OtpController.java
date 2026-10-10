package com.a1.notify.api;

import com.a1.notify.domain.Channel;
import com.a1.notify.otp.OtpService;
import com.a1.notify.security.CurrentProject;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "OTP")
@RestController
@RequestMapping("/otp")
public class OtpController {

    public record SendRequest(@Schema(description = "EMAIL or SMS", example = "EMAIL") @NotNull Channel channel,
                              @Schema(description = "Email address or E.164 phone number", example = "ops@example.com") @NotBlank @Size(max = 320) String recipient,
                              @Schema(description = "What the code is for", example = "login") @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9_.-]+") String purpose,
                              @Schema(example = "en") @Size(max = 16) String locale) {
    }

    public record VerifyRequest(@Schema(description = "challengeId returned by /otp/send") @NotNull UUID challengeId,
                                @Schema(type = "string", example = "\"123456\"") @NotBlank @Size(max = 12) String code) {
    }

    private final OtpService otp;

    public OtpController(OtpService otp) {
        this.otp = otp;
    }

    @Operation(summary = "Send a one-time code (scope OTP)",
            description = "Sends synchronously by EMAIL or SMS and returns a challengeId; the code is never returned or stored in plain text. "
                    + "Resend cooldown and hourly cap return 429 with Retry-After. A delivery failure returns 502 OTP_DELIVERY_FAILED and leaves no usable challenge.")
    @PostMapping("/send")
    @PreAuthorize("hasAuthority('OTP')")
    public OtpService.SendResult send(@Valid @RequestBody SendRequest request) {
        return otp.send(CurrentProject.require().requireProjectId(), request.channel(), request.recipient(),
                request.purpose(), request.locale());
    }

    @Operation(summary = "Verify a one-time code (scope OTP)",
            description = "Single use. Wrong or reused code: 400 OTP_INVALID. Expired: 400 OTP_EXPIRED. Too many attempts: 429 OTP_ATTEMPTS_EXCEEDED.")
    @PostMapping("/verify")
    @PreAuthorize("hasAuthority('OTP')")
    public Map<String, Object> verify(@Valid @RequestBody VerifyRequest request) {
        otp.verify(CurrentProject.require().requireProjectId(), request.challengeId(), request.code());
        return Map.of("verified", true);
    }
}
