package com.yash.Notifyr.service;

import com.yash.Notifyr.dto.NotificationMessage;
import com.yash.Notifyr.entity.Notification;
import com.yash.Notifyr.entity.NotificationStatus;
import com.yash.Notifyr.repository.NotificationRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final NotificationRepository notificationRepository;
    private final MeterRegistry meterRegistry;

    @Value("${notification.exchange.name}")
    private String exchangeName;

    @Value("${notification.routing-key}")
    private String routingKey;

    @Value("${notification.retry.exchange}")
    private String retryExchangeName;

    @Value("${notification.retry.routing-key-30s}")
    private String retryRoutingKey30s;

    public boolean publishWithRetryFallback(Notification notification, NotificationMessage message) {
        try {
            rabbitTemplate.convertAndSend(exchangeName, routingKey, message);
            return true;
        } catch (Exception e) {
            notification.setStatus(NotificationStatus.WAITING_RETRY);
            notification.setFailureReason("Failed to publish to queue: " + e.getMessage());
            notificationRepository.save(notification);
            log.warn("Publish failed for notification {}, re-queuing via retry exchange: {}",
                    notification.getId(), e.getMessage());
        }

        NotificationMessage retryMessage = new NotificationMessage(
                message.getNotificationId(),
                message.getRecipientEmail(),
                message.getSubject(),
                message.getMessage(),
                message.getRetryCount()
        );

        try {
            rabbitTemplate.convertAndSend(retryExchangeName, retryRoutingKey30s, retryMessage);
            meterRegistry.counter("publish_retry_attempts_total", "channel",
                    notification.getChannel().toString()).increment();
            log.info("Notification {} scheduled for delayed re-publish via {}",
                    notification.getId(), retryRoutingKey30s);
            return true;
        } catch (Exception e) {
            notification.setStatus(NotificationStatus.FAILED);
            notification.setFailureReason("Failed to publish to queue (both attempts): " + e.getMessage());
            notificationRepository.save(notification);
            meterRegistry.counter("notifications_failed_total", "channel",
                    notification.getChannel().toString()).increment();
            log.error("Retry publish failed for notification {}: {}", notification.getId(), e.getMessage());
            return false;
        }
    }
}
