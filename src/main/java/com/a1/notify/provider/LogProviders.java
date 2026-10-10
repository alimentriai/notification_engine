package com.a1.notify.provider;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Dev/test providers: log metadata only (never recipient address or message content) and never touch AWS. */
public final class LogProviders {

    private static final Logger log = LoggerFactory.getLogger(LogProviders.class);

    private LogProviders() {
    }

    @Component
    @ConditionalOnProperty(name = "notify.providers.mode", havingValue = "log", matchIfMissing = true)
    public static class LogEmailProvider implements EmailProvider {
        @Override
        public String name() {
            return "log-email";
        }

        @Override
        public ProviderResult send(EmailMessage message) {
            log.info("log_email_sent correlationId={}", message.correlationId());
            return ProviderResult.sent("log-" + UUID.randomUUID());
        }
    }

    @Component
    @ConditionalOnProperty(name = "notify.providers.mode", havingValue = "log", matchIfMissing = true)
    public static class LogSmsProvider implements SmsProvider {
        @Override
        public String name() {
            return "log-sms";
        }

        @Override
        public ProviderResult send(SmsMessage message) {
            log.info("log_sms_sent correlationId={}", message.correlationId());
            return ProviderResult.sent("log-" + UUID.randomUUID());
        }
    }
}
