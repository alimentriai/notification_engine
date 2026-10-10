package com.a1.notify.provider.aws;

import com.a1.notify.provider.EmailProvider;
import com.a1.notify.provider.ProviderResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.Body;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.Destination;
import software.amazon.awssdk.services.sesv2.model.EmailContent;
import software.amazon.awssdk.services.sesv2.model.Message;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse;

/** Amazon SES v2 transactional email (HTML + plain text). Sender identity must be verified in SES. */
@Component
@ConditionalOnProperty(name = "notify.providers.mode", havingValue = "aws")
public class SesEmailProvider implements EmailProvider {

    private final SesV2Client client;

    public SesEmailProvider(SesV2Client client) {
        this.client = client;
    }

    @Override
    public String name() {
        return "ses";
    }

    @Override
    public ProviderResult send(EmailMessage message) {
        try {
            Body.Builder body = Body.builder();
            if (message.text() != null) {
                body.text(content(message.text()));
            }
            if (message.html() != null) {
                body.html(content(message.html()));
            }
            SendEmailRequest.Builder request = SendEmailRequest.builder()
                    .fromEmailAddress(message.from())
                    .destination(Destination.builder().toAddresses(message.to()).build())
                    .content(EmailContent.builder()
                            .simple(Message.builder().subject(content(message.subject())).body(body.build()).build())
                            .build());
            if (message.configurationSet() != null && !message.configurationSet().isBlank()) {
                request.configurationSetName(message.configurationSet());
            }
            SendEmailResponse response = client.sendEmail(request.build());
            return ProviderResult.sent(response.messageId());
        } catch (RuntimeException ex) {
            return AwsErrorClassifier.classify(ex);
        }
    }

    private static Content content(String data) {
        return Content.builder().data(data).charset("UTF-8").build();
    }
}
