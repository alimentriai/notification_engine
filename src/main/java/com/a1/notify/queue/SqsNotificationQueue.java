package com.a1.notify.queue;

import com.a1.notify.config.NotifyProperties;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

/** Message body is only {"notificationId":"..."}: no recipient data or content ever travels through SQS. */
@Component
@ConditionalOnProperty(name = "notify.queue.enabled", havingValue = "true")
public class SqsNotificationQueue implements NotificationQueue {

    private static final Logger log = LoggerFactory.getLogger(SqsNotificationQueue.class);

    private final SqsClient sqs;
    private final NotifyProperties props;

    public SqsNotificationQueue(SqsClient sqs, NotifyProperties props) {
        this.sqs = sqs;
        this.props = props;
    }

    @Override
    public void enqueue(UUID notificationId, long delaySeconds) {
        try {
            sqs.sendMessage(SendMessageRequest.builder()
                    .queueUrl(props.getQueue().getUrl())
                    .messageBody("{\"notificationId\":\"" + notificationId + "\"}")
                    .delaySeconds((int) Math.max(0, Math.min(900, delaySeconds)))
                    .build());
        } catch (RuntimeException ex) {
            // The row stays PENDING; the sweeper re-enqueues it after the grace period.
            log.warn("sqs_enqueue_failed notificationId={}", notificationId);
        }
    }

    @Override
    public long sweepGraceSeconds() {
        return 60;
    }
}
