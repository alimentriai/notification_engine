package com.a1.notify.maintenance;

import com.a1.notify.config.NotifyProperties;
import com.a1.notify.core.NotificationStore;
import com.a1.notify.domain.NotificationEntity;
import com.a1.notify.queue.NotificationQueue;
import com.a1.notify.repository.NotificationRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Safety net that makes the database the source of truth: picks up due PENDING rows (scheduled, retried after
 * the in-memory delay, or whose queue enqueue was lost) and rescues rows stuck in PROCESSING.
 */
@Component
public class DispatchSweeper {

    private final NotificationRepository notifications;
    private final NotificationStore store;
    private final NotificationQueue queue;
    private final NotifyProperties props;

    public DispatchSweeper(NotificationRepository notifications, NotificationStore store, NotificationQueue queue,
                           NotifyProperties props) {
        this.notifications = notifications;
        this.store = store;
        this.queue = queue;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${notify.sweep-interval-ms:5000}")
    public void sweep() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        List<UUID> due = notifications.findDueIds(now.minusSeconds(queue.sweepGraceSeconds()), PageRequest.of(0, 100));
        due.forEach(id -> queue.enqueue(id, 0));
        List<NotificationEntity> stuck = notifications.findStuck(now.minusSeconds(props.getProcessingTimeoutSeconds()));
        stuck.forEach(n -> store.reap(n.getId()));
    }
}
