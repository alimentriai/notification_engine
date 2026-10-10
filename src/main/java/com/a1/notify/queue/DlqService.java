package com.a1.notify.queue;

import com.a1.notify.config.NotifyProperties;
import com.a1.notify.core.NotificationStore;
import com.a1.notify.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

/** Operational recovery for the dead-letter queue: inspect, then safely redrive back to the work queue. */
@Service
@ConditionalOnProperty(name = "notify.queue.enabled", havingValue = "true")
public class DlqService {

    public record DlqMessage(String messageId, String notificationId, String body) {
    }

    private final SqsClient sqs;
    private final NotifyProperties props;
    private final NotificationStore store;
    private final ObjectMapper mapper;

    public DlqService(SqsClient sqs, NotifyProperties props, NotificationStore store, ObjectMapper mapper) {
        this.sqs = sqs;
        this.props = props;
        this.store = store;
        this.mapper = mapper;
    }

    public long approximateSize() {
        requireDlq();
        String value = sqs.getQueueAttributes(GetQueueAttributesRequest.builder()
                .queueUrl(props.getQueue().getDlqUrl())
                .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES)
                .build()).attributes().get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES);
        return value == null ? 0 : Long.parseLong(value);
    }

    /** Looks at messages without hiding them from other consumers (visibility timeout 0). Bodies hold only ids. */
    public List<DlqMessage> peek(int max) {
        requireDlq();
        List<DlqMessage> out = new ArrayList<>();
        for (Message m : sqs.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(props.getQueue().getDlqUrl())
                .maxNumberOfMessages(Math.max(1, Math.min(max, 10)))
                .visibilityTimeout(0)
                .build()).messages()) {
            out.add(new DlqMessage(m.messageId(), idOf(m), m.body()));
        }
        return out;
    }

    /** Moves up to {@code max} messages back to the work queue and makes their rows eligible again. */
    public int redrive(int max) {
        requireDlq();
        int moved = 0;
        while (moved < max) {
            List<Message> batch = sqs.receiveMessage(ReceiveMessageRequest.builder()
                    .queueUrl(props.getQueue().getDlqUrl())
                    .maxNumberOfMessages(Math.min(10, max - moved))
                    .visibilityTimeout(60)
                    .build()).messages();
            if (batch.isEmpty()) {
                break;
            }
            for (Message m : batch) {
                // Reset the row first: a fast worker must find it PENDING, otherwise it would drop the message.
                String id = idOf(m);
                if (id != null) {
                    store.resetForRedrive(UUID.fromString(id));
                }
                sqs.sendMessage(SendMessageRequest.builder().queueUrl(props.getQueue().getUrl()).messageBody(m.body()).build());
                sqs.deleteMessage(DeleteMessageRequest.builder()
                        .queueUrl(props.getQueue().getDlqUrl()).receiptHandle(m.receiptHandle()).build());
                moved++;
            }
        }
        return moved;
    }

    private String idOf(Message m) {
        try {
            String id = mapper.readTree(m.body()).path("notificationId").asText(null);
            return id == null ? null : UUID.fromString(id).toString();
        } catch (Exception ex) {
            return null;
        }
    }

    private void requireDlq() {
        if (props.getQueue().getDlqUrl() == null || props.getQueue().getDlqUrl().isBlank()) {
            throw new ApiException(HttpStatus.CONFLICT, "DLQ_NOT_CONFIGURED", "notify.queue.dlq-url is not configured");
        }
    }
}
