package com.satyam.notificationworker.api;

import com.satyam.notificationworker.domain.Notification;
import com.satyam.notificationworker.domain.NotificationStatus;

import java.time.Instant;
import java.util.UUID;

public record NotificationResponse(
        UUID id,
        String to,
        String subject,
        NotificationStatus status,
        int attempts,
        String lastError,
        Instant createdAt,
        Instant updatedAt,
        Instant sentAt
) {
    public static NotificationResponse from(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getToEmail(),
                notification.getSubject(),
                notification.getStatus(),
                notification.getAttempts(),
                notification.getLastError(),
                notification.getCreatedAt(),
                notification.getUpdatedAt(),
                notification.getSentAt()
        );
    }
}
