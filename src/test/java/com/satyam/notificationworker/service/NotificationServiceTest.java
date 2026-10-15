package com.satyam.notificationworker.service;

import com.satyam.notificationworker.domain.Notification;
import com.satyam.notificationworker.domain.NotificationStatus;
import com.satyam.notificationworker.domain.OutboxEvent;
import com.satyam.notificationworker.metrics.NotificationMetrics;
import com.satyam.notificationworker.repo.NotificationRepository;
import com.satyam.notificationworker.repo.OutboxEventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private NotificationRepository repository;

    @Mock
    private OutboxEventRepository outboxRepository;

    private NotificationService service;

    @BeforeEach
    void setUp() {
        service = new NotificationService(repository, outboxRepository, new NotificationMetrics(new SimpleMeterRegistry()));
    }

    @Test
    void createPersistsPendingAndWritesOutbox() {
        when(repository.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));
        when(outboxRepository.save(any(OutboxEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        Notification created = service.create("user@example.com", "Hello", "World");

        assertThat(created.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(created.getToEmail()).isEqualTo("user@example.com");

        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().getNotificationId()).isEqualTo(created.getId());
        assertThat(outboxCaptor.getValue().getPublishedAt()).isNull();
    }

    @Test
    void getRequiredThrowsWhenMissing() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getRequired(id))
                .isInstanceOf(NotificationNotFoundException.class);
    }
}
