package com.a1.notify.otp;

import com.a1.notify.config.NotifyProperties;
import com.a1.notify.core.NotificationStore;
import com.a1.notify.core.RecipientValidator;
import com.a1.notify.domain.AttemptEntity;
import com.a1.notify.domain.Category;
import com.a1.notify.domain.Channel;
import com.a1.notify.domain.NotificationEntity;
import com.a1.notify.domain.NotificationStatus;
import com.a1.notify.domain.OtpChallengeEntity;
import com.a1.notify.domain.Priority;
import com.a1.notify.domain.ProjectEntity;
import com.a1.notify.domain.TemplateEntity;
import com.a1.notify.provider.ChannelSender;
import com.a1.notify.provider.ProviderResult;
import com.a1.notify.repository.AttemptRepository;
import com.a1.notify.repository.NotificationRepository;
import com.a1.notify.repository.OtpChallengeRepository;
import com.a1.notify.repository.ProjectRepository;
import com.a1.notify.repository.TemplateRepository;
import com.a1.notify.security.Hashing;
import com.a1.notify.security.RateLimiter;
import com.a1.notify.template.TemplateService;
import com.a1.notify.template.TemplateService.Rendered;
import com.a1.notify.web.ApiException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * One-time codes with their own controls: only a salted HMAC of the code is stored, codes expire, verification
 * attempts are capped, resends have a cooldown and an hourly cap, and a challenge can be used once. The raw code is
 * rendered in memory, sent synchronously and never persisted or logged.
 */
@Service
public class OtpService {

    private static final SecureRandom RANDOM = new SecureRandom();

    public record SendResult(UUID challengeId, int expiresInSeconds, int resendAfterSeconds) {
    }

    private final OtpChallengeRepository challenges;
    private final ProjectRepository projects;
    private final TemplateRepository templateRepo;
    private final TemplateService templates;
    private final ChannelSender sender;
    private final NotificationRepository notifications;
    private final AttemptRepository attempts;
    private final NotificationStore store;
    private final RateLimiter rateLimiter;
    private final NotifyProperties props;

    public OtpService(OtpChallengeRepository challenges, ProjectRepository projects, TemplateRepository templateRepo,
                      TemplateService templates, ChannelSender sender, NotificationRepository notifications,
                      AttemptRepository attempts, NotificationStore store, RateLimiter rateLimiter, NotifyProperties props) {
        this.challenges = challenges;
        this.projects = projects;
        this.templateRepo = templateRepo;
        this.templates = templates;
        this.sender = sender;
        this.notifications = notifications;
        this.attempts = attempts;
        this.store = store;
        this.rateLimiter = rateLimiter;
        this.props = props;
    }

    public SendResult send(Long projectId, Channel channel, String address, String purpose, String locale) {
        if (channel != Channel.EMAIL && channel != Channel.SMS) {
            throw ApiException.badRequest("UNSUPPORTED_CHANNEL", "OTP can be sent by EMAIL or SMS");
        }
        if (!Boolean.TRUE.equals(props.channelFlags().get(channel.name()))) {
            throw ApiException.badRequest("CHANNEL_DISABLED", "Channel " + channel + " is not enabled");
        }
        String normalized = channel == Channel.EMAIL ? RecipientValidator.email(address).toLowerCase() : RecipientValidator.phone(address);
        ProjectEntity project = projects.findById(projectId).filter(ProjectEntity::isActive)
                .orElseThrow(() -> ApiException.notFound("Project not found"));
        rateLimiter.check(project, 1);

        NotifyProperties.Otp cfg = props.getOtp();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String recipientHash = Hashing.hmacSha256Hex(cfg.getPepper(), projectId + ":" + channel + ":" + normalized);

        Optional<OtpChallengeEntity> last = challenges.findFirstByProjectIdAndRecipientHashOrderByCreatedAtDesc(projectId, recipientHash);
        if (last.isPresent()) {
            long since = Duration.between(last.get().getCreatedAt(), now).getSeconds();
            if (since < cfg.getResendCooldownSeconds()) {
                throw ApiException.tooMany("OTP_RESEND_COOLDOWN", "Please wait before requesting another code",
                        cfg.getResendCooldownSeconds() - since);
            }
        }
        if (challenges.countByProjectIdAndRecipientHashAndCreatedAtAfter(projectId, recipientHash, now.minusHours(1))
                >= cfg.getMaxSendsPerHour()) {
            throw ApiException.tooMany("OTP_SEND_LIMIT", "Too many codes requested; try again later", 3600);
        }

        String code = generateCode(cfg.getLength());
        String salt = randomSalt();
        OtpChallengeEntity challenge = new OtpChallengeEntity();
        challenge.setId(UUID.randomUUID());
        challenge.setProjectId(projectId);
        challenge.setRecipientHash(recipientHash);
        challenge.setPurpose(purpose);
        challenge.setChannel(channel);
        challenge.setSalt(salt);
        challenge.setCodeHash(hash(salt, challenge.getId(), code));
        challenge.setCreatedAt(now);
        challenge.setExpiresAt(now.plusSeconds(cfg.getTtlSeconds()));
        challenge.setMaxAttempts(cfg.getMaxAttempts());
        challenge.setAttempts(0);

        String loc = locale == null || locale.isBlank() ? "en" : locale;
        Map<String, String> vars = Map.of("code", code, "ttlMinutes", String.valueOf(Math.max(1, cfg.getTtlSeconds() / 60)),
                "purpose", purpose);
        Integer templateVersion = null;
        Rendered rendered = renderTemplate(projectId, channel, loc, vars);
        if (rendered == null) {
            rendered = builtIn(channel, vars);
        } else {
            templateVersion = templateRepo.findFirstByProjectIdAndCodeAndChannelAndLocaleOrderByVersionDesc(
                    projectId, "otp", channel, loc).map(TemplateEntity::getVersion).orElse(null);
        }

        OffsetDateTime started = OffsetDateTime.now(ZoneOffset.UTC);
        ProviderResult result = channel == Channel.EMAIL
                ? sender.sendEmail(project, normalized, rendered, MDC.get("correlationId"))
                : sender.sendSms(project, normalized, rendered, "Transactional", MDC.get("correlationId"));
        recordAudit(projectId, recipientHash, purpose, channel, templateVersion, loc, result, started,
                channel == Channel.EMAIL ? sender.emailProviderName() : sender.smsProviderName());
        if (!result.isSent()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "OTP_DELIVERY_FAILED", "The code could not be delivered");
        }
        challenges.save(challenge);
        return new SendResult(challenge.getId(), cfg.getTtlSeconds(), cfg.getResendCooldownSeconds());
    }

    /** Returns normally on success; throws ApiException otherwise. */
    public void verify(Long projectId, UUID challengeId, String code) {
        OtpChallengeEntity c = challenges.findByIdAndProjectId(challengeId, projectId)
                .orElseThrow(() -> ApiException.badRequest("OTP_INVALID", "Invalid or expired code"));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (c.getConsumedAt() != null) {
            throw ApiException.badRequest("OTP_INVALID", "Invalid or expired code");
        }
        if (c.getExpiresAt().isBefore(now)) {
            throw ApiException.badRequest("OTP_EXPIRED", "The code has expired");
        }
        if (challenges.incrementAttempts(challengeId) == 0) {
            throw ApiException.tooMany("OTP_ATTEMPTS_EXCEEDED", "Too many incorrect attempts", props.getOtp().getTtlSeconds());
        }
        boolean matches = Hashing.constantTimeEquals(c.getCodeHash(), hash(c.getSalt(), c.getId(), code == null ? "" : code.trim()));
        if (!matches) {
            throw ApiException.badRequest("OTP_INVALID", "Invalid or expired code");
        }
        if (challenges.consume(challengeId, now) == 0) {
            throw ApiException.badRequest("OTP_INVALID", "Invalid or expired code");
        }
    }

    private Rendered renderTemplate(Long projectId, Channel channel, String locale, Map<String, String> vars) {
        try {
            TemplateEntity template = templates.resolve(projectId, "otp", channel, locale);
            return templates.render(template, vars);
        } catch (ApiException ex) {
            if ("TEMPLATE_NOT_FOUND".equals(ex.getCode())) {
                return null;
            }
            throw ex;
        }
    }

    private Rendered builtIn(Channel channel, Map<String, String> vars) {
        if (channel == Channel.EMAIL) {
            return templates.renderRaw("Your verification code",
                    "<p>Your verification code is <strong>{{code}}</strong>. It expires in {{ttlMinutes}} minutes.</p>",
                    "Your verification code is {{code}}. It expires in {{ttlMinutes}} minutes.", vars);
        }
        return templates.renderRaw(null, null, "Your verification code is {{code}}. It expires in {{ttlMinutes}} minutes.", vars);
    }

    /** Audit row without recipient address, variables or code. */
    private void recordAudit(Long projectId, String recipientHash, String purpose, Channel channel, Integer templateVersion,
                             String locale, ProviderResult result, OffsetDateTime started, String providerName) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        NotificationEntity n = new NotificationEntity();
        n.setId(UUID.randomUUID());
        n.setProjectId(projectId);
        n.setRequestId(UUID.randomUUID().toString());
        n.setRecipientRef("otp:" + recipientHash.substring(0, 16));
        n.setType("OTP_" + purpose.toUpperCase().replaceAll("[^A-Z0-9_]", "_"));
        n.setCategory(Category.OTP);
        n.setChannel(channel);
        n.setTemplateCode("otp");
        n.setLocale(locale);
        n.setTemplateVersion(templateVersion);
        n.setPriority(Priority.URGENT);
        n.setStatus(result.isSent() ? NotificationStatus.SENT : NotificationStatus.FAILED);
        n.setCorrelationId(MDC.get("correlationId"));
        n.setAttempts(1);
        n.setRetryable(false);
        n.setLastErrorCode(result.errorCode());
        n.setProviderMessageId(result.providerMessageId());
        n.setCreatedAt(now);
        n.setUpdatedAt(now);
        n.setSentAt(result.isSent() ? now : null);
        notifications.save(n);
        AttemptEntity attempt = new AttemptEntity();
        attempt.setNotificationId(n.getId());
        attempt.setAttemptNo(1);
        attempt.setProvider(providerName);
        attempt.setOutcome(result.outcome().name());
        attempt.setErrorCode(result.errorCode());
        attempt.setStartedAt(started);
        attempt.setFinishedAt(now);
        attempts.save(attempt);
        store.recordEvent(n.getId(), null, n.getStatus(), result.errorCode());
    }

    private String hash(String salt, UUID challengeId, String code) {
        return Hashing.hmacSha256Hex(props.getOtp().getPepper(), salt + ":" + challengeId + ":" + code);
    }

    private static String generateCode(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(RANDOM.nextInt(10));
        }
        return sb.toString();
    }

    private static String randomSalt() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
