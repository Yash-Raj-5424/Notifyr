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
    public ResponseEntity<NotificationResponse> send(@Valid @RequestBody NotificationRequest request,
                                                     @RequestHeader(value="Idempotency-Key",
                                                             required=false) String idempotencyKey) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(notificationService.sendNotification(request, idempotencyKey));
    }


    @GetMapping("/{id}")
    public ResponseEntity<NotificationResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(notificationService.getNotificationById(id));
    }


    @GetMapping("/health")
    public String healthCheck() {
        return "Notification service is up and running!";
    }
}
