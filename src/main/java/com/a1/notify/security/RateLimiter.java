package com.a1.notify.security;

import com.a1.notify.config.NotifyProperties;
import com.a1.notify.domain.ProjectEntity;
import com.a1.notify.repository.NotificationRepository;
import com.a1.notify.web.ApiException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Per-project token bucket (requests per minute) plus a rolling 24h quota backed by the database. */
@Component
public class RateLimiter {

    private final NotificationRepository notifications;
    private final NotifyProperties props;
    private final ConcurrentHashMap<Long, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimiter(NotificationRepository notifications, NotifyProperties props) {
        this.notifications = notifications;
        this.props = props;
    }

    public void check(ProjectEntity project, int newNotifications) {
        int perMinute = project.getRateLimitPerMin() > 0 ? project.getRateLimitPerMin() : props.getRateLimit().getDefaultPerMinute();
        Bucket bucket = buckets.computeIfAbsent(project.getId(), id -> new Bucket(perMinute));
        if (!bucket.tryAcquire(perMinute, newNotifications)) {
            throw ApiException.tooMany("RATE_LIMITED", "Project rate limit exceeded", 60);
        }
        int quota = project.getDailyQuota() > 0 ? project.getDailyQuota() : props.getRateLimit().getDefaultDailyQuota();
        long used = notifications.countByProjectIdAndCreatedAtAfter(project.getId(), OffsetDateTime.now(ZoneOffset.UTC).minusDays(1));
        if (used + newNotifications > quota) {
            throw ApiException.tooMany("QUOTA_EXCEEDED", "Daily notification quota exceeded", 3600);
        }
    }

    private static final class Bucket {
        private double tokens;
        private long lastRefillNanos = System.nanoTime();

        Bucket(int capacity) {
            this.tokens = capacity;
        }

        synchronized boolean tryAcquire(int capacity, int cost) {
            long now = System.nanoTime();
            tokens = Math.min(capacity, tokens + (now - lastRefillNanos) / 60_000_000_000.0 * capacity);
            lastRefillNanos = now;
            if (tokens >= cost) {
                tokens -= cost;
                return true;
            }
            return false;
        }
    }
}
