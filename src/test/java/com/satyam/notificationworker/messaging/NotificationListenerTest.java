package com.satyam.notificationworker.messaging;

import com.satyam.notificationworker.config.NotificationProperties;
import com.satyam.notificationworker.domain.Notification;
import com.satyam.notificationworker.domain.NotificationStatus;
import com.satyam.notificationworker.email.EmailSender;
import com.satyam.notificationworker.repo.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationListenerTest {

    @Mock
    private NotificationRepository repository;

    @Mock
    private EmailSender emailSender;

    private NotificationProperties properties;
    private NotificationListener listener;

    @BeforeEach
    void setUp() {
        properties = new NotificationProperties();
        properties.setMaxAttempts(3);
        listener = new NotificationListener(repository, emailSender, properties);
    }

    @Test
    void skipsAlreadySentNotification() {
        UUID id = UUID.randomUUID();
        Notification notification = new Notification(id, "a@b.com", "Hi", "Body");
        notification.setStatus(NotificationStatus.SENT);
        when(repository.findById(id)).thenReturn(Optional.of(notification));

        listener.onMessage(new NotificationMessage(id));

        verify(emailSender, never()).send(any(), any(), any());
        verify(repository, never()).save(any());
    }

    @Test
    void skipsAlreadyFailedNotification() {
        UUID id = UUID.randomUUID();
        Notification notification = new Notification(id, "a@b.com", "Hi", "Body");
        notification.setStatus(NotificationStatus.FAILED);
        when(repository.findById(id)).thenReturn(Optional.of(notification));

        listener.onMessage(new NotificationMessage(id));

        verify(emailSender, never()).send(any(), any(), any());
    }

    @Test
    void marksSentOnSuccessfulEmail() {
        UUID id = UUID.randomUUID();
        Notification notification = new Notification(id, "a@b.com", "Hi", "Body");
        when(repository.findById(id)).thenReturn(Optional.of(notification));
        when(repository.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        listener.onMessage(new NotificationMessage(id));

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(repository, times(2)).save(captor.capture());
        Notification saved = captor.getAllValues().get(1);
        assertThat(saved.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(saved.getAttempts()).isEqualTo(1);
        assertThat(saved.getSentAt()).isNotNull();
        assertThat(saved.getLastError()).isNull();
        verify(emailSender).send("a@b.com", "Hi", "Body");
    }

    @Test
    void requeuesWhenAttemptsRemain() {
        UUID id = UUID.randomUUID();
        Notification notification = new Notification(id, "a@b.com", "Hi", "Body");
        when(repository.findById(id)).thenReturn(Optional.of(notification));
        when(repository.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new RuntimeException("smtp down")).when(emailSender).send(any(), any(), any());

        assertThatThrownBy(() -> listener.onMessage(new NotificationMessage(id)))
                .isInstanceOf(RuntimeException.class)
                .isNotInstanceOf(AmqpRejectAndDontRequeueException.class);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(repository, times(2)).save(captor.capture());
        Notification afterFailure = captor.getAllValues().get(1);
        assertThat(afterFailure.getStatus()).isEqualTo(NotificationStatus.PROCESSING);
        assertThat(afterFailure.getAttempts()).isEqualTo(1);
        assertThat(afterFailure.getLastError()).contains("smtp down");
    }

    @Test
    void routesToDlqWhenAttemptsExhausted() {
        UUID id = UUID.randomUUID();
        Notification notification = new Notification(id, "a@b.com", "Hi", "Body");
        notification.setAttempts(2);
        when(repository.findById(id)).thenReturn(Optional.of(notification));
        when(repository.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new RuntimeException("smtp down")).when(emailSender).send(any(), any(), any());

        assertThatThrownBy(() -> listener.onMessage(new NotificationMessage(id)))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(repository, times(2)).save(captor.capture());
        Notification failed = captor.getAllValues().get(1);
        assertThat(failed.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(failed.getAttempts()).isEqualTo(3);
        assertThat(failed.getLastError()).contains("smtp down");
    }

    @Test
    void rejectsMissingNotificationToDlq() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> listener.onMessage(new NotificationMessage(id)))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);

        verify(emailSender, never()).send(any(), any(), any());
    }
}
