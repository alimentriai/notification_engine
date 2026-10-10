package com.a1.notify.provider.aws;

import com.a1.notify.provider.ProviderResult;
import com.a1.notify.provider.SmsProvider;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.PublishResponse;

/**
 * Amazon SNS direct SMS publish. India DLT identifiers (entity id, template id, sender id) come from
 * project/template configuration; SNS production SMS access and DLT registration are account prerequisites.
 */
@Component
@ConditionalOnProperty(name = "notify.providers.mode", havingValue = "aws")
public class SnsSmsProvider implements SmsProvider {

    private final SnsClient client;

    public SnsSmsProvider(SnsClient client) {
        this.client = client;
    }

    @Override
    public String name() {
        return "sns";
    }

    @Override
    public ProviderResult send(SmsMessage message) {
        try {
            Map<String, MessageAttributeValue> attributes = new HashMap<>();
            attributes.put("AWS.SNS.SMS.SMSType", attr(message.smsType()));
            putIfPresent(attributes, "AWS.SNS.SMS.SenderID", message.senderId());
            putIfPresent(attributes, "AWS.MM.SMS.EntityId", message.entityId());
            putIfPresent(attributes, "AWS.MM.SMS.TemplateId", message.templateId());
            PublishResponse response = client.publish(PublishRequest.builder()
                    .phoneNumber(message.phone())
                    .message(message.text())
                    .messageAttributes(attributes)
                    .build());
            return ProviderResult.sent(response.messageId());
        } catch (RuntimeException ex) {
            return AwsErrorClassifier.classify(ex);
        }
    }

    private static void putIfPresent(Map<String, MessageAttributeValue> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, attr(value));
        }
    }

    private static MessageAttributeValue attr(String value) {
        return MessageAttributeValue.builder().dataType("String").stringValue(value).build();
    }
}
