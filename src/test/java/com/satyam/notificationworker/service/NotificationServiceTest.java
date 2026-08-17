package com.satyam.notificationworker.service;

import com.satyam.notificationworker.domain.Notification;
import com.satyam.notificationworker.domain.NotificationStatus;
import com.satyam.notificationworker.messaging.NotificationMessage;
import com.satyam.notificationworker.messaging.RabbitConfig;
import com.satyam.notificationworker.repo.NotificationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private NotificationRepository repository;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @InjectMocks
    private NotificationService service;

    @Test
    void createPersistsPendingAndPublishes() {
        when(repository.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        Notification created = service.create("user@example.com", "Hello", "World");

        assertThat(created.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(created.getToEmail()).isEqualTo("user@example.com");

        ArgumentCaptor<NotificationMessage> messageCaptor = ArgumentCaptor.forClass(NotificationMessage.class);
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitConfig.EXCHANGE),
                eq(RabbitConfig.ROUTING_KEY),
                messageCaptor.capture()
        );
        assertThat(messageCaptor.getValue().notificationId()).isEqualTo(created.getId());
    }

    @Test
    void createMarksFailedWhenPublishFails() {
        when(repository.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new AmqpException("broker down")).when(rabbitTemplate)
                .convertAndSend(eq(RabbitConfig.EXCHANGE), eq(RabbitConfig.ROUTING_KEY), any(NotificationMessage.class));

        Notification created = service.create("user@example.com", "Hello", "World");

        assertThat(created.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(created.getLastError()).contains("Broker publish failed");
    }

    @Test
    void getRequiredThrowsWhenMissing() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getRequired(id))
                .isInstanceOf(NotificationNotFoundException.class);
    }
}
