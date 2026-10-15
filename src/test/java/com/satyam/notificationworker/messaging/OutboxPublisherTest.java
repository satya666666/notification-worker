package com.satyam.notificationworker.messaging;

import com.satyam.notificationworker.config.NotificationProperties;
import com.satyam.notificationworker.domain.OutboxEvent;
import com.satyam.notificationworker.metrics.NotificationMetrics;
import com.satyam.notificationworker.repo.OutboxEventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

    @Mock
    private OutboxEventRepository outboxRepository;

    @Mock
    private RabbitTemplate rabbitTemplate;

    private OutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new OutboxPublisher(
                outboxRepository,
                rabbitTemplate,
                new NotificationProperties(),
                new NotificationMetrics(new SimpleMeterRegistry())
        );
    }

    @Test
    void publishesUnpublishedEventsAndMarksThem() {
        UUID notificationId = UUID.randomUUID();
        OutboxEvent event = new OutboxEvent(notificationId);
        when(outboxRepository.findUnpublished(any(Pageable.class))).thenReturn(List.of(event));

        publisher.publishPending();

        verify(rabbitTemplate).convertAndSend(
                eq(RabbitConfig.EXCHANGE),
                eq(RabbitConfig.ROUTING_KEY),
                eq(new NotificationMessage(notificationId))
        );
        assertThat(event.getPublishedAt()).isNotNull();
    }

    @Test
    void leavesEventUnpublishedWhenBrokerFails() {
        UUID notificationId = UUID.randomUUID();
        OutboxEvent event = new OutboxEvent(notificationId);
        when(outboxRepository.findUnpublished(any(Pageable.class))).thenReturn(List.of(event));
        doThrow(new AmqpException("broker down")).when(rabbitTemplate)
                .convertAndSend(eq(RabbitConfig.EXCHANGE), eq(RabbitConfig.ROUTING_KEY), any(NotificationMessage.class));

        publisher.publishPending();

        assertThat(event.getPublishedAt()).isNull();
    }

    @Test
    void doesNothingWhenOutboxIsEmpty() {
        when(outboxRepository.findUnpublished(any(Pageable.class))).thenReturn(List.of());

        publisher.publishPending();

        verify(rabbitTemplate, never()).convertAndSend(any(String.class), any(String.class), any(Object.class));
    }
}
