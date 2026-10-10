package com.a1.notify.queue;

import java.util.UUID;

/** Hands a notification id to a worker. Implementations: SQS (production) or in-process executor (local). */
public interface NotificationQueue {

    /** Schedule processing, optionally after a delay. Must not throw. */
    void enqueue(UUID notificationId, long delaySeconds);

    /** Seconds a PENDING row may sit before the sweeper re-enqueues it (guards against lost enqueues). */
    long sweepGraceSeconds();
}
