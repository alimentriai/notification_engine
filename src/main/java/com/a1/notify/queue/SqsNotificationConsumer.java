package com.a1.notify.queue;

import com.a1.notify.config.NotifyProperties;
import com.a1.notify.core.NotificationDispatcher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

/**
 * Long-polls the work queue. A message is deleted only after the notification was handled (sent, failed
 * permanently, or rescheduled). Unexpected errors and malformed bodies are left in the queue so SQS redelivers them
 * and, after maxReceiveCount, moves them to the dead-letter queue for investigation.
 */
@Component
@ConditionalOnProperty(name = "notify.queue.enabled", havingValue = "true")
public class SqsNotificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(SqsNotificationConsumer.class);

    private final SqsClient sqs;
    private final NotifyProperties props;
    private final NotificationDispatcher dispatcher;
    private final ObjectMapper mapper;

    public SqsNotificationConsumer(SqsClient sqs, NotifyProperties props, NotificationDispatcher dispatcher, ObjectMapper mapper) {
        this.sqs = sqs;
        this.props = props;
        this.dispatcher = dispatcher;
        this.mapper = mapper;
    }

    @Scheduled(fixedDelayString = "${notify.queue.poll-delay-ms:500}")
    public void poll() {
        NotifyProperties.Queue q = props.getQueue();
        List<Message> messages;
        try {
            messages = sqs.receiveMessage(ReceiveMessageRequest.builder()
                    .queueUrl(q.getUrl())
                    .maxNumberOfMessages(q.getMaxMessages())
                    .waitTimeSeconds(q.getWaitSeconds())
                    .visibilityTimeout(q.getVisibilityTimeoutSeconds())
                    .build()).messages();
        } catch (RuntimeException ex) {
            log.warn("sqs_poll_failed type={}", ex.getClass().getSimpleName());
            return;
        }
        for (Message message : messages) {
            handle(message);
        }
    }

    private void handle(Message message) {
        UUID id;
        try {
            JsonNode json = mapper.readTree(message.body());
            id = UUID.fromString(json.path("notificationId").asText());
        } catch (Exception ex) {
            log.warn("sqs_message_malformed messageId={}", message.messageId());
            return;
        }
        try {
            dispatcher.dispatch(id);
            sqs.deleteMessage(DeleteMessageRequest.builder()
                    .queueUrl(props.getQueue().getUrl())
                    .receiptHandle(message.receiptHandle())
                    .build());
        } catch (RuntimeException ex) {
            log.warn("sqs_message_failed notificationId={} type={}", id, ex.getClass().getSimpleName());
        }
    }
}
