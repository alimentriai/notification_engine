package com.a1.notify.maintenance;

import com.a1.notify.config.NotifyProperties;
import com.a1.notify.repository.AttemptRepository;
import com.a1.notify.repository.EventRepository;
import com.a1.notify.repository.NotificationRepository;
import com.a1.notify.repository.OtpChallengeRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Wipes recipient contact data early and deletes old rows, per configurable retention. */
@Component
public class RetentionJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

    private final NotificationRepository notifications;
    private final AttemptRepository attempts;
    private final EventRepository events;
    private final OtpChallengeRepository otps;
    private final NotifyProperties props;

    public RetentionJob(NotificationRepository notifications, AttemptRepository attempts, EventRepository events,
                        OtpChallengeRepository otps, NotifyProperties props) {
        this.notifications = notifications;
        this.attempts = attempts;
        this.events = events;
        this.otps = otps;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${notify.retention.cleanup-interval-ms:3600000}", initialDelay = 60000)
    @Transactional
    public void run() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        int wiped = notifications.wipeContactData(now.minusDays(props.getRetention().getContactDays()));
        int deleted = 0;
        List<UUID> ids;
        do {
            ids = notifications.findExpiredIds(now.minusDays(props.getRetention().getDays()), PageRequest.of(0, 500));
            if (!ids.isEmpty()) {
                attempts.deleteByNotificationIdIn(ids);
                events.deleteByNotificationIdIn(ids);
                notifications.deleteAllById(ids);
                notifications.flush();
                deleted += ids.size();
            }
        } while (ids.size() == 500);
        otps.deleteByExpiresAtBefore(now.minusDays(1));
        log.info("retention_completed contactWiped={} deleted={}", wiped, deleted);
    }
}
