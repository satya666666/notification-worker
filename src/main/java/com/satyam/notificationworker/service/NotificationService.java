package com.satyam.notificationworker.service;

import com.satyam.notificationworker.domain.Notification;
import com.satyam.notificationworker.domain.NotificationStatus;
import com.satyam.notificationworker.messaging.NotificationMessage;
import com.satyam.notificationworker.messaging.RabbitConfig;
import com.satyam.notificationworker.repo.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository repository;
    private final RabbitTemplate rabbitTemplate;

    public NotificationService(NotificationRepository repository, RabbitTemplate rabbitTemplate) {
        this.repository = repository;
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * Persist first (repository save commits in its own transaction), then publish.
     * Publishing inside an open DB transaction races the listener against an uncommitted row.
     */
    public Notification create(String to, String subject, String body) {
        Notification notification = repository.save(new Notification(UUID.randomUUID(), to, subject, body));

        try {
            rabbitTemplate.convertAndSend(
                    RabbitConfig.EXCHANGE,
                    RabbitConfig.ROUTING_KEY,
                    new NotificationMessage(notification.getId())
            );
            log.info("Published notification message id={}", notification.getId());
        } catch (AmqpException ex) {
            log.error("Failed to publish notification id={}: {}", notification.getId(), ex.getMessage());
            notification.setStatus(NotificationStatus.FAILED);
            notification.setLastError("Broker publish failed: " + ex.getMessage());
            notification = repository.save(notification);
        }

        return notification;
    }

    @Transactional(readOnly = true)
    public Notification getRequired(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new NotificationNotFoundException(id));
    }
}
