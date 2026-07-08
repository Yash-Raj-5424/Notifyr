package com.yash.Notifyr.service;

import com.yash.Notifyr.dto.NotificationMessage;
import com.yash.Notifyr.dto.NotificationRequest;
import com.yash.Notifyr.dto.NotificationResponse;
import com.yash.Notifyr.entity.Notification;
import com.yash.Notifyr.entity.NotificationStatus;
import com.yash.Notifyr.exception.NotificationNotFoundException;
import com.yash.Notifyr.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final RabbitTemplate rabbitTemplate;
    private final RedisTemplate<String, NotificationResponse> redisTemplate;

    @Value("${notification.exchange.name}")
    private String exchangeName;

    @Value("${notification.routing-key}")
    private String routingKey;

    private static final String IDEMPOTENCY_KEY_PREFIX = "idempotency:";
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);

    public NotificationResponse sendNotification(NotificationRequest request, String idempotencyKey){

        // Check if the idempotency key exists in Redis
        if(idempotencyKey != null && !idempotencyKey.isBlank()) {
            String redisKey = IDEMPOTENCY_KEY_PREFIX + idempotencyKey;
            NotificationResponse cachedResponse = redisTemplate.opsForValue().get(redisKey);

            if (cachedResponse != null) {
                log.info("Key already processed. Returning cached response for key: {}", idempotencyKey);
                return cachedResponse;
            }
        }


        Notification notification = Notification.builder()
                .recipientEmail(request.getRecipientEmail())
                .subject(request.getSubject())
                .message(request.getMessage())
                .status(NotificationStatus.QUEUED)
                .build();

        notification = notificationRepository.save(notification);   // save to db as QUEUED

        // build a message to send to rabbitmq
        NotificationMessage message = new NotificationMessage(
                notification.getId(),
                notification.getRecipientEmail(),
                notification.getSubject(),
                notification.getMessage(),
                0
        );

        // publish to rabbitmq
        try {
            rabbitTemplate.convertAndSend(exchangeName, routingKey, message);
            log.info("Notification {} queued for recipient {}", notification.getId(), notification.getRecipientEmail());
        }catch (Exception e){
            notification.setStatus(NotificationStatus.FAILED);
            notification.setFailureReason("Failed to publish to queue: {}" + e.getMessage());
            notificationRepository.save(notification);
            log.error("Failed to publish notification {} to queue: {}"
                    , notification.getId(), e.getMessage());
        }

        NotificationResponse response = mapToResponse(notification);

        // cache the response
        if(idempotencyKey != null && !idempotencyKey.isBlank()) {
            String redisKey = IDEMPOTENCY_KEY_PREFIX + idempotencyKey;
            redisTemplate.opsForValue().set(redisKey, response, IDEMPOTENCY_TTL);
        }

        return response;
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

    public NotificationResponse getNotificationById(Long id) {

        Notification notification = notificationRepository.findById(id)
                .orElseThrow(() -> new NotificationNotFoundException("Notification: " + id + " not found"));

        return mapToResponse(notification);

    }
}
