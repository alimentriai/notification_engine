package com.a1.notify.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** All externalised settings. Prefix: notify. */
@Getter
@Setter
@ConfigurationProperties(prefix = "notify")
public class NotifyProperties {

    /** AWS region used by every client. */
    private String region = "ap-south-1";
    /** Optional endpoint override (LocalStack). Leave blank for real AWS. */
    private String awsEndpoint = "";
    /** SHA-256 of nothing is stored: the raw bootstrap key is compared by hash. Blank disables platform admin. */
    private String bootstrapAdminKey = "";

    private Providers providers = new Providers();
    private Channels channels = new Channels();
    private Email email = new Email();
    private Sms sms = new Sms();
    private Queue queue = new Queue();
    private Retry retry = new Retry();
    private RateLimit rateLimit = new RateLimit();
    private Retention retention = new Retention();
    private Otp otp = new Otp();
    /** Category name -> default channels used when a request omits channels. */
    private Map<String, List<String>> defaultChannels = new HashMap<>();
    private int processingTimeoutSeconds = 300;
    private int sweepIntervalMs = 5000;

    @Getter
    @Setter
    public static class Providers {
        /** log = never contacts AWS (default, safe for dev/tests); aws = SES + SNS. */
        private String mode = "log";
        private Push push = new Push();
    }

    @Getter
    @Setter
    public static class Push {
        private boolean enabled = false;
    }

    @Getter
    @Setter
    public static class Channels {
        private boolean inAppEnabled = true;
        private boolean emailEnabled = true;
        private boolean smsEnabled = true;
        private boolean pushEnabled = false;
    }

    @Getter
    @Setter
    public static class Email {
        private String defaultSender = "no-reply@example.com";
        private String configurationSet = "";
    }

    @Getter
    @Setter
    public static class Sms {
        /** Transactional or Promotional. */
        private String defaultType = "Transactional";
        private String defaultSenderId = "";
    }

    @Getter
    @Setter
    public static class Queue {
        private boolean enabled = false;
        private String url = "";
        private String dlqUrl = "";
        private int waitSeconds = 10;
        private int visibilityTimeoutSeconds = 60;
        private int pollDelayMs = 500;
        private int maxMessages = 10;
    }

    @Getter
    @Setter
    public static class Retry {
        private int maxAttempts = 5;
        private int baseDelaySeconds = 10;
        private int maxDelaySeconds = 900;
        private double jitter = 0.2;
    }

    @Getter
    @Setter
    public static class RateLimit {
        private int defaultPerMinute = 120;
        private int defaultDailyQuota = 50000;
    }

    @Getter
    @Setter
    public static class Retention {
        /** Whole notification rows are deleted after this many days. */
        private int days = 90;
        /** Recipient contact data and template variables are wiped after this many days. */
        private int contactDays = 7;
        private long cleanupIntervalMs = 3_600_000L;
    }

    @Getter
    @Setter
    public static class Otp {
        private int length = 6;
        private int ttlSeconds = 300;
        private int maxAttempts = 5;
        private int resendCooldownSeconds = 60;
        private int maxSendsPerHour = 5;
        /** Server-side secret mixed into OTP hashes. Must be set in production. */
        private String pepper = "change-me-in-production";
    }

    public List<String> defaultChannelsFor(String category) {
        return defaultChannels.getOrDefault(category, new ArrayList<>());
    }

    public Map<String, Boolean> channelFlags() {
        Map<String, Boolean> flags = new HashMap<>();
        flags.put("IN_APP", channels.isInAppEnabled());
        flags.put("EMAIL", channels.isEmailEnabled());
        flags.put("SMS", channels.isSmsEnabled());
        flags.put("PUSH", channels.isPushEnabled() && providers.getPush().isEnabled());
        return flags;
    }
}
