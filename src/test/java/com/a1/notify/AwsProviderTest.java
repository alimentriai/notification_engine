package com.a1.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.a1.notify.provider.EmailProvider.EmailMessage;
import com.a1.notify.provider.ProviderResult;
import com.a1.notify.provider.SmsProvider.SmsMessage;
import com.a1.notify.provider.aws.SesEmailProvider;
import com.a1.notify.provider.aws.SnsSmsProvider;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.MessageRejectedException;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse;
import software.amazon.awssdk.services.sesv2.model.TooManyRequestsException;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.InvalidParameterException;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.PublishResponse;

class AwsProviderTest {

    private static EmailMessage email() {
        return new EmailMessage("from@x.com", "to@x.com", "Subj", "<p>h</p>", "t", "cfg", "corr");
    }

    @Test
    void sesSendsHtmlAndTextWithConfigSet() {
        SesV2Client client = mock(SesV2Client.class);
        when(client.sendEmail(any(SendEmailRequest.class))).thenReturn(SendEmailResponse.builder().messageId("m-1").build());
        ProviderResult result = new SesEmailProvider(client).send(email());
        assertThat(result.isSent()).isTrue();
        assertThat(result.providerMessageId()).isEqualTo("m-1");
        ArgumentCaptor<SendEmailRequest> captor = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(client).sendEmail(captor.capture());
        assertThat(captor.getValue().fromEmailAddress()).isEqualTo("from@x.com");
        assertThat(captor.getValue().configurationSetName()).isEqualTo("cfg");
        assertThat(captor.getValue().content().simple().body().html().data()).isEqualTo("<p>h</p>");
        assertThat(captor.getValue().content().simple().body().text().data()).isEqualTo("t");
    }

    @Test
    void sesThrottlingIsTransient() {
        SesV2Client client = mock(SesV2Client.class);
        when(client.sendEmail(any(SendEmailRequest.class))).thenThrow(TooManyRequestsException.builder()
                .statusCode(429).awsErrorDetails(AwsErrorDetails.builder().errorCode("TooManyRequestsException").build()).build());
        ProviderResult result = new SesEmailProvider(client).send(email());
        assertThat(result.outcome()).isEqualTo(ProviderResult.Outcome.TRANSIENT_FAILURE);
        assertThat(result.errorCode()).isEqualTo("TooManyRequestsException");
    }

    @Test
    void sesRejectionIsPermanent() {
        SesV2Client client = mock(SesV2Client.class);
        when(client.sendEmail(any(SendEmailRequest.class))).thenThrow(MessageRejectedException.builder()
                .statusCode(400).awsErrorDetails(AwsErrorDetails.builder().errorCode("MessageRejected").build()).build());
        assertThat(new SesEmailProvider(client).send(email()).outcome()).isEqualTo(ProviderResult.Outcome.PERMANENT_FAILURE);
    }

    @Test
    void serverErrorsAndNetworkFailuresAreTransient() {
        SesV2Client client = mock(SesV2Client.class);
        when(client.sendEmail(any(SendEmailRequest.class)))
                .thenThrow(SdkClientException.create("timeout"))
                .thenThrow(software.amazon.awssdk.services.sesv2.model.SesV2Exception.builder().statusCode(503)
                        .awsErrorDetails(AwsErrorDetails.builder().errorCode("ServiceUnavailable").build()).build());
        SesEmailProvider provider = new SesEmailProvider(client);
        assertThat(provider.send(email()).outcome()).isEqualTo(ProviderResult.Outcome.TRANSIENT_FAILURE);
        assertThat(provider.send(email()).outcome()).isEqualTo(ProviderResult.Outcome.TRANSIENT_FAILURE);
    }

    @Test
    void snsSendsDltAttributes() {
        SnsClient client = mock(SnsClient.class);
        when(client.publish(any(PublishRequest.class))).thenReturn(PublishResponse.builder().messageId("s-1").build());
        ProviderResult result = new SnsSmsProvider(client).send(
                new SmsMessage("+919876543210", "hello", "Transactional", "A1CRSH", "ENT123", "TPL456", "corr"));
        assertThat(result.isSent()).isTrue();
        ArgumentCaptor<PublishRequest> captor = ArgumentCaptor.forClass(PublishRequest.class);
        verify(client).publish(captor.capture());
        PublishRequest request = captor.getValue();
        assertThat(request.phoneNumber()).isEqualTo("+919876543210");
        assertThat(request.messageAttributes().get("AWS.SNS.SMS.SMSType").stringValue()).isEqualTo("Transactional");
        assertThat(request.messageAttributes().get("AWS.SNS.SMS.SenderID").stringValue()).isEqualTo("A1CRSH");
        assertThat(request.messageAttributes().get("AWS.MM.SMS.EntityId").stringValue()).isEqualTo("ENT123");
        assertThat(request.messageAttributes().get("AWS.MM.SMS.TemplateId").stringValue()).isEqualTo("TPL456");
    }

    @Test
    void snsInvalidNumberIsPermanentAndBlankDltOmitted() {
        SnsClient client = mock(SnsClient.class);
        when(client.publish(any(PublishRequest.class))).thenThrow(InvalidParameterException.builder().statusCode(400)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("InvalidParameter").build()).build());
        ProviderResult result = new SnsSmsProvider(client).send(
                new SmsMessage("+919876543210", "hello", "Transactional", null, null, null, "corr"));
        assertThat(result.outcome()).isEqualTo(ProviderResult.Outcome.PERMANENT_FAILURE);
        assertThat(result.errorCode()).isEqualTo("InvalidParameter");
    }
}
