package com.satyam.notificationworker.api;

import com.satyam.notificationworker.domain.Notification;
import com.satyam.notificationworker.service.NotificationService;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/notifications")
@ConditionalOnProperty(name = "notification.roles.api", havingValue = "true", matchIfMissing = true)
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @PostMapping
    public ResponseEntity<CreateNotificationResponse> create(@Valid @RequestBody CreateNotificationRequest request) {
        Notification created = notificationService.create(request.to(), request.subject(), request.body());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new CreateNotificationResponse(created.getId(), created.getStatus()));
    }

    @GetMapping("/{id}")
    public NotificationResponse get(@PathVariable UUID id) {
        return NotificationResponse.from(notificationService.getRequired(id));
    }
}
