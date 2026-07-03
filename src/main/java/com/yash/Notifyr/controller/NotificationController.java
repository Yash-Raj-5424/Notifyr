package com.yash.Notifyr.controller;

import com.yash.Notifyr.dto.NotificationRequest;
import com.yash.Notifyr.dto.NotificationResponse;
import com.yash.Notifyr.entity.Notification;
import com.yash.Notifyr.service.NotificationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @PostMapping("/send")
    public ResponseEntity<NotificationResponse> send(@Valid @RequestBody NotificationRequest request) {
        Notification notification = notificationService.sendNotification(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(mapToResponse(notification));
    }


    @GetMapping("/{id}")
    public ResponseEntity<NotificationResponse> getById(@PathVariable Long id) {
        Notification notification = notificationService.getNotificationById(id);
        return ResponseEntity.ok(mapToResponse(notification));
    }


    private NotificationResponse mapToResponse(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getRecipientEmail(),
                notification.getStatus(),
                notification.getSubject(),
                notification.getFailureReason(),
                notification.getCreatedAt(),
                notification.getUpdatedAt()
        );
    }

    @GetMapping("/health")
    public String healthCheck() {
        return "Notification service is up and running!";
    }
}
