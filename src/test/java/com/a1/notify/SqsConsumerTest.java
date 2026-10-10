package com.a1.notify;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.a1.notify.config.NotifyProperties;
import com.a1.notify.core.NotificationDispatcher;
import com.a1.notify.queue.SqsNotificationConsumer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

class SqsConsumerTest {

    private SqsClient sqs;
    private NotificationDispatcher dispatcher;
    private SqsNotificationConsumer consumer;

    @BeforeEach
    void setUp() {
        sqs = mock(SqsClient.class);
        dispatcher = mock(NotificationDispatcher.class);
        NotifyProperties props = new NotifyProperties();
        props.getQueue().setUrl("http://q");
        consumer = new SqsNotificationConsumer(sqs, props, dispatcher, new ObjectMapper());
    }

    private void receive(String body) {
        when(sqs.receiveMessage(any(ReceiveMessageRequest.class))).thenReturn(ReceiveMessageResponse.builder()
                .messages(List.of(Message.builder().messageId("m").receiptHandle("rh").body(body).build())).build());
    }

    @Test
    void handledMessageIsDeleted() {
        UUID id = UUID.randomUUID();
        receive("{\"notificationId\":\"" + id + "\"}");
        consumer.poll();
        verify(dispatcher).dispatch(id);
        verify(sqs, times(1)).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void failedProcessingIsLeftForRedeliveryAndDlq() {
        UUID id = UUID.randomUUID();
        receive("{\"notificationId\":\"" + id + "\"}");
        doThrow(new IllegalStateException("db down")).when(dispatcher).dispatch(id);
        consumer.poll();
        verify(sqs, never()).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void malformedMessageIsNotDeletedSoItReachesTheDlq() {
        receive("not json");
        consumer.poll();
        verify(dispatcher, never()).dispatch(any());
        verify(sqs, never()).deleteMessage(any(DeleteMessageRequest.class));
    }
}
