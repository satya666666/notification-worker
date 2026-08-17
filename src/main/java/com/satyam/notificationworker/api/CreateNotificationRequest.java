package com.satyam.notificationworker.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record CreateNotificationRequest(
        @NotBlank(message = "to is required")
        @Email(message = "to must be a valid email")
        String to,

        @NotBlank(message = "subject is required")
        String subject,

        @NotBlank(message = "body is required")
        String body
) {
}
