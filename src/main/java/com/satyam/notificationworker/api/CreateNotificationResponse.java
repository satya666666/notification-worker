package com.satyam.notificationworker.api;

import com.satyam.notificationworker.domain.NotificationStatus;

import java.util.UUID;

public record CreateNotificationResponse(UUID id, NotificationStatus status) {
}
