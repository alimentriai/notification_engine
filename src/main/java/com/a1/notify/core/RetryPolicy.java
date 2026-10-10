package com.a1.notify.core;

import com.a1.notify.config.NotifyProperties;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

/** Bounded exponential backoff with jitter. */
@Component
public class RetryPolicy {

    private final NotifyProperties.Retry retry;

    public RetryPolicy(NotifyProperties props) {
        this.retry = props.getRetry();
    }

    public int maxAttempts() {
        return retry.getMaxAttempts();
    }

    public boolean canRetry(int attemptsSoFar) {
        return attemptsSoFar < retry.getMaxAttempts();
    }

    /** Delay before attempt (attemptsSoFar + 1). */
    public long delaySeconds(int attemptsSoFar) {
        double exp = retry.getBaseDelaySeconds() * Math.pow(2, Math.max(0, attemptsSoFar - 1));
        double capped = Math.min(exp, retry.getMaxDelaySeconds());
        double jitter = 1 + (ThreadLocalRandom.current().nextDouble() * 2 - 1) * retry.getJitter();
        return Math.max(1, Math.round(Math.min(capped * jitter, retry.getMaxDelaySeconds())));
    }
}
