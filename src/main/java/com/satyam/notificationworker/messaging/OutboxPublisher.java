package com.satyam.notificationworker.messaging;

import com.satyam.notificationworker.config.NotificationProperties;
import com.satyam.notificationworker.domain.OutboxEvent;
import com.satyam.notificationworker.metrics.NotificationMetrics;
import com.satyam.notificationworker.repo.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Component
@ConditionalOnProperty(name = "notification.roles.api", havingValue = "true", matchIfMissing = true)
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository outboxRepository;
    private final RabbitTemplate rabbitTemplate;
    private final NotificationProperties properties;
    private final NotificationMetrics metrics;

    public OutboxPublisher(
            OutboxEventRepository outboxRepository,
            RabbitTemplate rabbitTemplate,
            NotificationProperties properties,
            NotificationMetrics metrics
    ) {
        this.outboxRepository = outboxRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${notification.outbox.poll-interval-ms:200}")
    @Transactional
    public void publishPending() {
        List<OutboxEvent> batch = outboxRepository.findUnpublished(
                PageRequest.of(0, properties.getOutbox().getBatchSize())
        );
        for (OutboxEvent event : batch) {
            try {
                rabbitTemplate.convertAndSend(
                        RabbitConfig.EXCHANGE,
                        RabbitConfig.ROUTING_KEY,
                        new NotificationMessage(event.getNotificationId())
                );
                event.markPublished(Instant.now());
                metrics.incrementPublished();
                log.info("Published outbox event id={} notificationId={}", event.getId(), event.getNotificationId());
            } catch (AmqpException ex) {
                log.warn("Outbox publish failed notificationId={}: {}", event.getNotificationId(), ex.getMessage());
                break;
            }
        }
    }
}
