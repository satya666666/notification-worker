package com.satyam.notificationworker.messaging;

import com.satyam.notificationworker.config.NotificationProperties;
import com.satyam.notificationworker.domain.Notification;
import com.satyam.notificationworker.domain.NotificationStatus;
import com.satyam.notificationworker.email.EmailSender;
import com.satyam.notificationworker.metrics.NotificationMetrics;
import com.satyam.notificationworker.repo.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@ConditionalOnProperty(name = "notification.roles.worker", havingValue = "true", matchIfMissing = true)
public class NotificationListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationListener.class);

    private final NotificationRepository repository;
    private final EmailSender emailSender;
    private final NotificationProperties properties;
    private final RabbitTemplate rabbitTemplate;
    private final NotificationMetrics metrics;

    public NotificationListener(
            NotificationRepository repository,
            EmailSender emailSender,
            NotificationProperties properties,
            RabbitTemplate rabbitTemplate,
            NotificationMetrics metrics
    ) {
        this.repository = repository;
        this.emailSender = emailSender;
        this.properties = properties;
        this.rabbitTemplate = rabbitTemplate;
        this.metrics = metrics;
    }

    @RabbitListener(queues = RabbitConfig.QUEUE)
    public void onMessage(NotificationMessage message) {
        if (message == null || message.notificationId() == null) {
            throw new AmqpRejectAndDontRequeueException("Invalid notification message");
        }

        Notification notification = repository.findById(message.notificationId()).orElse(null);
        if (notification == null) {
            log.error("Notification not found for message id={}, routing to DLQ", message.notificationId());
            throw new AmqpRejectAndDontRequeueException("Notification not found: " + message.notificationId());
        }

        if (notification.getStatus() == NotificationStatus.SENT
                || notification.getStatus() == NotificationStatus.FAILED) {
            log.info("Skipping terminal notification id={} status={}", notification.getId(), notification.getStatus());
            return;
        }

        notification.setStatus(NotificationStatus.PROCESSING);
        notification.incrementAttempts();
        repository.save(notification);

        try {
            emailSender.send(notification.getToEmail(), notification.getSubject(), notification.getBody());
            notification.setStatus(NotificationStatus.SENT);
            notification.setSentAt(Instant.now());
            notification.setLastError(null);
            repository.save(notification);
            metrics.incrementSent();
            log.info("Notification sent id={} attempts={}", notification.getId(), notification.getAttempts());
        } catch (RuntimeException ex) {
            String error = truncate(ex.getMessage(), 2000);
            notification.setLastError(error);

            if (notification.getAttempts() < properties.getMaxAttempts()) {
                repository.save(notification);
                long delayMs = properties.retryBackoffMs(notification.getAttempts());
                rabbitTemplate.convertAndSend(
                        RabbitConfig.RETRY_EXCHANGE,
                        RabbitConfig.RETRY_ROUTING_KEY,
                        new NotificationMessage(notification.getId()),
                        retryMessage -> {
                            retryMessage.getMessageProperties().setExpiration(String.valueOf(delayMs));
                            return retryMessage;
                        }
                );
                metrics.incrementRetried();
                log.warn(
                        "Notification send failed id={} attempt={}/{}, retry in {}ms: {}",
                        notification.getId(),
                        notification.getAttempts(),
                        properties.getMaxAttempts(),
                        delayMs,
                        error
                );
                return;
            }

            notification.setStatus(NotificationStatus.FAILED);
            repository.save(notification);
            metrics.incrementFailed();
            log.error(
                    "Notification send exhausted retries id={} attempts={}, routing to DLQ: {}",
                    notification.getId(),
                    notification.getAttempts(),
                    error
            );
            throw new AmqpRejectAndDontRequeueException(
                    "Email send exhausted for " + notification.getId() + ": " + error,
                    ex
            );
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "Unknown error";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
