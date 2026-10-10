package com.a1.notify.provider;

public interface EmailProvider {

    String name();

    ProviderResult send(EmailMessage message);

    record EmailMessage(String from, String to, String subject, String html, String text,
                        String configurationSet, String correlationId) {
    }
}
