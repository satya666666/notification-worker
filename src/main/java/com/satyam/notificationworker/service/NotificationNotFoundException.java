package com.satyam.notificationworker.service;

import java.util.UUID;

public class NotificationNotFoundException extends RuntimeException {

    private final UUID id;

    public NotificationNotFoundException(UUID id) {
        super("Notification not found: " + id);
        this.id = id;
    }

    public UUID getId() {
        return id;
    }
}
