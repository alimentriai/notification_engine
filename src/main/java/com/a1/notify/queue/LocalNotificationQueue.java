package com.a1.notify.queue;

import com.a1.notify.core.NotificationDispatcher;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/** In-process queue used when SQS is disabled. Delayed work is picked up by the DB sweeper. */
@Component
@ConditionalOnProperty(name = "notify.queue.enabled", havingValue = "false", matchIfMissing = true)
public class LocalNotificationQueue implements NotificationQueue {

    private static final Logger log = LoggerFactory.getLogger(LocalNotificationQueue.class);

    private final Executor executor;
    private final NotificationDispatcher dispatcher;

    public LocalNotificationQueue(@Qualifier("notifyExecutor") Executor executor, @Lazy NotificationDispatcher dispatcher) {
        this.executor = executor;
        this.dispatcher = dispatcher;
    }

    @Override
    public void enqueue(UUID notificationId, long delaySeconds) {
        if (delaySeconds > 0) {
            return;
        }
        try {
            executor.execute(() -> dispatcher.dispatch(notificationId));
        } catch (RuntimeException ex) {
            log.warn("local_enqueue_failed notificationId={}", notificationId);
        }
    }

    @Override
    public long sweepGraceSeconds() {
        return 0;
    }
}
