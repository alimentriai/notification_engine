package com.a1.notify.provider;

public interface SmsProvider {

    String name();

    ProviderResult send(SmsMessage message);

    /** senderId / entityId / templateId carry India DLT identifiers when configured. */
    record SmsMessage(String phone, String text, String smsType, String senderId,
                      String entityId, String templateId, String correlationId) {
    }
}
