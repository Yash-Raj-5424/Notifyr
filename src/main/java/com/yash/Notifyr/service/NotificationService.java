package com.yash.Notifyr.service;

import com.yash.Notifyr.dto.NotificationMessage;
import com.yash.Notifyr.dto.NotificationRequest;
import com.yash.Notifyr.dto.NotificationResponse;
import com.yash.Notifyr.entity.Notification;
import com.yash.Notifyr.entity.NotificationChannel;
import com.yash.Notifyr.entity.NotificationStatus;
import com.yash.Notifyr.exception.IdempotencyInProgressException;
import com.yash.Notifyr.exception.NotificationNotFoundException;
import com.yash.Notifyr.exception.ServiceUnavailableException;
import com.yash.Notifyr.repository.NotificationRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final NotificationPublisher notificationPublisher;
    private final RedisTemplate<String, NotificationResponse> redisTemplate;
    private final MeterRegistry meterRegistry;

    @Value("${notification.idempotency.fail-open:true}")
    private boolean failOpen;   // read fil-open from config (sender can decide whether or not to)

    private static final String IDEMPOTENCY_KEY_PREFIX = "idempotency:";
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);
    private static final Duration IDEMPOTENCY_CLAIM_TTL = Duration.ofSeconds(60);

    public NotificationResponse sendNotification(NotificationRequest request, String idempotencyKey){

        boolean hasIdempotencyKey = idempotencyKey != null && !idempotencyKey.isBlank();
        String redisKey = hasIdempotencyKey ? IDEMPOTENCY_KEY_PREFIX + idempotencyKey : null;

        // Atomically claim the key before processing - prevents concurrent duplicates
        if(hasIdempotencyKey){
            try {
                NotificationResponse cached = redisTemplate.opsForValue().get(redisKey);

                if(cached != null){
                    if(cached.getId() == null){
                        meterRegistry.counter("idempotency_conflicts_total").increment();
                        log.warn("Request for key {} is already in progress", idempotencyKey);
                        throw new IdempotencyInProgressException(idempotencyKey);
                    }
                    log.info("Key already processed. Returning cached response for key: {}", idempotencyKey);
                    return cached;
                }

                // SET NX - only one concurrent request can claim the key
                boolean claimed = Boolean.TRUE.equals(
                        redisTemplate.opsForValue()
                                .setIfAbsent(redisKey, new NotificationResponse(), IDEMPOTENCY_CLAIM_TTL));

                if(!claimed){
                    NotificationResponse current = redisTemplate.opsForValue().get(redisKey);

                    if(current != null && current.getId() == null){
                        meterRegistry.counter("idempotency_conflicts_total").increment();
                        log.warn("Concurrent request for key {} detected - losing request rejected"
                                , idempotencyKey);
                        throw new IdempotencyInProgressException(idempotencyKey);
                    }

                    if(current != null){
                        log.info("Concurrent request for key {} already completed. "
                                + "Returning its response", idempotencyKey);
                        return current;
                    }

                    // claim expired between checks - safe to reject, nothing was duplicated
                    meterRegistry.counter("idempotency_conflicts_total").increment();
                    throw new IdempotencyInProgressException(idempotencyKey);
                }
            }catch(DataAccessException e){
                meterRegistry.counter("idempotency_failures_total", "op", "get").increment();

                if(!failOpen){
                    log.error("Redis unavailable and idempotency fail-open is disabled, "
                            + "rejecting request for key {}: {}", idempotencyKey, e.getMessage()
                    );
                    throw new ServiceUnavailableException(
                            "Idempotency store unavailable. Retry the request later"
                    );
                }

                log.warn("Redis unavailable - idempotency check skipped for key {}, "
                        + "duplicates possible: {}", idempotencyKey, e.getMessage()
                );
            }
        }


        Notification notification = Notification.builder()
                .recipientEmail(request.getRecipientEmail())
                .subject(request.getSubject())
                .message(request.getMessage())
                .status(NotificationStatus.QUEUED)
                .channel(NotificationChannel.EMAIL)
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
        boolean published = notificationPublisher.publishWithRetryFallback(notification, message);
        if(published){
            log.info("Notification {} queued for recipient {}", notification.getId(), notification.getRecipientEmail());
        }

        NotificationResponse response = mapToResponse(notification);

        // replace the claim marker with the real response
        if(hasIdempotencyKey) {
            try {
                redisTemplate.opsForValue().set(redisKey, response, IDEMPOTENCY_TTL);
            } catch (DataAccessException e) {
                meterRegistry.counter("idempotency_failures_total", "op", "set").increment();
                log.warn("Failed to cache idempotency response for key {} - "
                        + "send already succeeded, returning normally: {}", idempotencyKey, e.getMessage());
            }
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
