package com.a1.notify.provider;

public interface PushProvider {

    String name();

    ProviderResult send(PushMessage message);

    record PushMessage(String platform, String token, String title, String body, String correlationId) {
    }
}
