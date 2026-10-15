package com.satyam.notificationworker.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class NotificationMetrics {

    private final Counter created;
    private final Counter published;
    private final Counter sent;
    private final Counter failed;
    private final Counter retried;

    public NotificationMetrics(MeterRegistry registry) {
        this.created = Counter.builder("notifications.created")
                .description("Notification jobs accepted")
                .register(registry);
        this.published = Counter.builder("notifications.published")
                .description("Outbox events published to RabbitMQ")
                .register(registry);
        this.sent = Counter.builder("notifications.sent")
                .description("Emails sent successfully")
                .register(registry);
        this.failed = Counter.builder("notifications.failed")
                .description("Notifications marked failed")
                .register(registry);
        this.retried = Counter.builder("notifications.retried")
                .description("Delayed retries scheduled")
                .register(registry);
    }

    public void incrementCreated() {
        created.increment();
    }

    public void incrementPublished() {
        published.increment();
    }

    public void incrementSent() {
        sent.increment();
    }

    public void incrementFailed() {
        failed.increment();
    }

    public void incrementRetried() {
        retried.increment();
    }
}
