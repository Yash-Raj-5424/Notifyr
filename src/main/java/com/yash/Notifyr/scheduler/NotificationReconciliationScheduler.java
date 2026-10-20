package com.yash.Notifyr.scheduler;

import com.yash.Notifyr.dto.NotificationMessage;
import com.yash.Notifyr.entity.Notification;
import com.yash.Notifyr.entity.NotificationStatus;
import com.yash.Notifyr.repository.NotificationRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(
        name = "notification.reconciliation.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class NotificationReconciliationScheduler {

    private final NotificationRepository notificationRepository;
    private final RabbitTemplate rabbitTemplate;
    private final MeterRegistry meterRegistry;

    @Value("${notification.exchange.name}")
    private String exchangeName;

    @Value("${notification.routing-key}")
    private String routingKey;

    @Value("${notification.reconciliation.stuck-after-minutes:2}")
    private long stuckAfterMinutes;

    @Value("${notification.reconciliation.batch-size:100}")
    private int batchSize;

    private static final Set<NotificationStatus> STUCK_STATUSES = Set.of(
            NotificationStatus.QUEUED,
            NotificationStatus.PROCESSING,
            NotificationStatus.WAITING_RETRY
    );

    @Scheduled(fixedRateString = "${notification.reconciliation.poll-ms:60000}")
    public void requeueStuckNotifications(){

        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(stuckAfterMinutes);

        List<Notification> stuckNotifications = notificationRepository
                .findByStatusInAndUpdatedAtBefore(
                        STUCK_STATUSES,
                        cutoff,
                        PageRequest.of(0, batchSize)
                );

        if(stuckNotifications.isEmpty()){
            return;
        }

        log.warn("Found {} stale notification(s) older than {} minute(s) - re-queuing",
                stuckNotifications.size(), stuckAfterMinutes);

        for(Notification notification : stuckNotifications){

            NotificationStatus previousStatus = notification.getStatus();

            // normalize to QUEUED - the save bumps updatedAt, so a failed publish
            // backs off until the next sweep instead of being retried every tick
            notification.setStatus(NotificationStatus.QUEUED);
            notificationRepository.save(notification);

            NotificationMessage message = new NotificationMessage(
                    notification.getId(),
                    notification.getRecipientEmail(),
                    notification.getSubject(),
                    notification.getMessage(),
                    notification.getRetryCount()
            );

            try{
                rabbitTemplate.convertAndSend(exchangeName, routingKey, message);

                meterRegistry.counter("notifications_reconciled_total",
                        "channel", notification.getChannel().toString(),
                        "from_status", previousStatus.toString()).increment();

                log.info("Stale notification {} (was {}) re-queued for delivery",
                        notification.getId(), previousStatus);
            }catch(Exception e){
                /* not using NotificationPublisher here as its fallback can mark
                 the notification FAILED, which a broker outage during a sweep must not do
                 */
                meterRegistry.counter("reconciliation_failures_total").increment();

                log.error("Failed to re-queue stale notification {} - will retry next sweep: {}"
                        , notification.getId(), e.getMessage());
            }
        }
    }
}
