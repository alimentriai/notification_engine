package com.a1.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.a1.notify.config.NotifyProperties;
import com.a1.notify.core.NotificationStore;
import com.a1.notify.queue.DlqService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

class DlqServiceTest {

    @Test
    void redriveResetsTheRowBeforeRequeueingThenDeletesFromDlq() {
        SqsClient sqs = mock(SqsClient.class);
        NotificationStore store = mock(NotificationStore.class);
        NotifyProperties props = new NotifyProperties();
        props.getQueue().setUrl("http://main");
        props.getQueue().setDlqUrl("http://dlq");
        UUID id = UUID.randomUUID();
        Message message = Message.builder().messageId("m").receiptHandle("rh").body("{\"notificationId\":\"" + id + "\"}").build();
        when(sqs.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(ReceiveMessageResponse.builder().messages(List.of(message)).build())
                .thenReturn(ReceiveMessageResponse.builder().messages(List.of()).build());

        int moved = new DlqService(sqs, props, store, new ObjectMapper()).redrive(10);

        assertThat(moved).isEqualTo(1);
        InOrder order = inOrder(store, sqs);
        order.verify(store).resetForRedrive(id);
        order.verify(sqs).sendMessage(any(SendMessageRequest.class));
        order.verify(sqs).deleteMessage(any(DeleteMessageRequest.class));
    }
}
