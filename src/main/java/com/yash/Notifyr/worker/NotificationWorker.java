package com.yash.Notifyr.worker;

import com.yash.Notifyr.dto.NotificationMessage;
import com.yash.Notifyr.entity.Notification;
import com.yash.Notifyr.entity.NotificationStatus;
import com.yash.Notifyr.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Random;

@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationWorker {

    private final NotificationRepository notificationRepository;
    private final RabbitTemplate rabbitTemplate;

    @Value("${notification.retry.exchange}")
    private String retryExchangeName;

    @Value("${notification.retry.routing-key-30s}")
    private String retryRoutingKey30s;

    @Value("${notification.retry.routing-key-2m}")
    private String retryRoutingKey2m;

    @Value("${notification.retry.routing-key-5m}")
    private String retryRoutingKey5m;

    @Value("${notification.dlq.exchange}")
    private String dlqExchangeName;

    @Value("${notification.dlq.routing-key}")
    private String dlqRoutingKey;

    private static final int MAX_RETRIES = 3;

    @RabbitListener(queues = "${notification.queue.name}")
    public void handleNotification(NotificationMessage message){
        log.info("Received notification job for id={}, recipient={}", message.getNotificationId(),
                message.getRecipientEmail());

        Optional<Notification> optionalNotification = notificationRepository.findById(message.getNotificationId());

        if(optionalNotification.isEmpty()){
            log.warn("Notification id={} not found in DB, skipping", message.getNotificationId());
            return;
        }

        Notification notification = optionalNotification.get();
        notification.setStatus(NotificationStatus.PROCESSING);
        notificationRepository.save(notification);

        try{
            simulateSend(message);

            notification.setStatus(NotificationStatus.SENT);
            notification.setRetryCount(message.getRetryCount());
            notificationRepository.save(notification);

            log.info("Notification id={} sent successfully", notification.getId());
        }catch(Exception e) {
            handleFailure(notification, message, e);
        }
    }

    private void handleFailure(Notification notification, NotificationMessage message, Exception e) {

        int nextRetryCount = message.getRetryCount() + 1;
        notification.setFailureReason(e.getMessage());
        notification.setRetryCount(nextRetryCount);

        if(nextRetryCount > MAX_RETRIES){

            // send to dlq permanently after max retries
            notification.setStatus(NotificationStatus.FAILED);
            notificationRepository.save(notification);

            NotificationMessage dlqMessage = new NotificationMessage(
                    message.getNotificationId(),
                    message.getRecipientEmail(),
                    message.getSubject(),
                    message.getMessage(),
                    nextRetryCount
            );

            rabbitTemplate.convertAndSend(dlqExchangeName, dlqRoutingKey, dlqMessage);
            log.error("Notification id={} failed after {} attempts. Sent to DLQ. Error: {}", notification.getId(),
                    nextRetryCount-1, e.getMessage());

            return;
        }

        // attempts remaining - sent to retry queue with delays
        notification.setStatus(NotificationStatus.FAILED);  // flips to PROCESSING on next attempt
        notificationRepository.save(notification);

        NotificationMessage retryMessage = new NotificationMessage(
                message.getNotificationId(),
                message.getRecipientEmail(),
                message.getSubject(),
                message.getMessage(),
                nextRetryCount
        );


        String routingKey = switch(nextRetryCount){
            case 1 -> retryRoutingKey30s;
            case 2 -> retryRoutingKey2m;
            case 3 -> retryRoutingKey5m;
            default -> retryRoutingKey5m;  // fallback to 5m for any unexpected case
        };

        rabbitTemplate.convertAndSend(retryExchangeName, routingKey, retryMessage);

        log.warn("Notification id={} failed on attempt {}. Retrying in {}. Error: {}", notification.getId(),
                nextRetryCount, routingKey, e.getMessage());
    }

    @Value("${notification.simulate-failure-rate:0.1}")
    private double simulateFailureRate;
    private final Random random = new Random();

    private void simulateSend(NotificationMessage message) throws InterruptedException {

        Thread.sleep(1000);
        if(random.nextDouble() < simulateFailureRate){
            throw new RuntimeException("Simulated random send failure for testing");
        }

        if(message.getRecipientEmail().contains("fail")){
            throw new RuntimeException("Simulated send failure for testing");
        }

        log.info("Simulating email sent to {} | subject='{}' | body='{}'", message.getRecipientEmail(),
                message.getSubject(), message.getMessage());
    }

}
