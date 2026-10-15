package com.satyam.notificationworker.service;

import com.satyam.notificationworker.domain.Notification;
import com.satyam.notificationworker.domain.OutboxEvent;
import com.satyam.notificationworker.metrics.NotificationMetrics;
import com.satyam.notificationworker.repo.NotificationRepository;
import com.satyam.notificationworker.repo.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository repository;
    private final OutboxEventRepository outboxRepository;
    private final NotificationMetrics metrics;

    public NotificationService(
            NotificationRepository repository,
            OutboxEventRepository outboxRepository,
            NotificationMetrics metrics
    ) {
        this.repository = repository;
        this.outboxRepository = outboxRepository;
        this.metrics = metrics;
    }

    /**
     * Persist the job and an outbox row in one transaction. The scheduled
     * {@code OutboxPublisher} publishes after commit so the listener never
     * races an uncommitted row, and a broker outage does not lose the job.
     */
    @Transactional
    public Notification create(String to, String subject, String body) {
        Notification notification = repository.save(new Notification(UUID.randomUUID(), to, subject, body));
        outboxRepository.save(new OutboxEvent(notification.getId()));
        metrics.incrementCreated();
        log.info("Queued notification id={} via outbox", notification.getId());
        return notification;
    }

    @Transactional(readOnly = true)
    public Notification getRequired(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new NotificationNotFoundException(id));
    }
}
