package com.yash.Notifyr.worker;

import com.yash.Notifyr.dto.NotificationMessage;
import com.yash.Notifyr.entity.Notification;
import com.yash.Notifyr.entity.NotificationChannel;
import com.yash.Notifyr.entity.NotificationStatus;
import com.yash.Notifyr.provider.EmailProvider;
import com.yash.Notifyr.provider.SmsProvider;
import com.yash.Notifyr.repository.NotificationRepository;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Optional;
import java.util.Random;

@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationWorker {

    private final NotificationRepository notificationRepository;
    private final RabbitTemplate rabbitTemplate;
    private final EmailProvider emailProvider;
    private final SmsProvider smsProvider;
    private final MeterRegistry meterRegistry;

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

    @Value("${notification.simulate-failure-rate:0.0}")
    private double simulateFailureRate;

    @Value("${notification.push.delay-ms:1000}")
    private long pushDelayMs;

    private static final int MAX_RETRIES = 3;
    private final Random random = new Random();

    @RabbitListener(queues = "${notification.queue.name}", ackMode = "MANUAL")
    public void handleNotification(NotificationMessage message, Channel channel
            , @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {

        try {
            processNotification(message);
        } catch (Exception e) {
            // infra failure (DB/broker) - drop instead of requeue to avoid a hot
            // redelivery loop; the reconciliation sweep re-drives if the row is still stuck
            meterRegistry.counter("worker_unhandled_failures_total").increment();
            log.error("Unexpected failure processing notification {} - nacking without requeue, "
                    + "reconciliation sweep will re-drive if still stuck: {}"
                    , message.getNotificationId(), e.getMessage());
            channel.basicNack(deliveryTag, false, false);
            return;
        }

        channel.basicAck(deliveryTag, false);
    }

    private void processNotification(NotificationMessage message) {
        log.info("Received notification job for id={}, recipient={}", message.getNotificationId(),
                message.getRecipientEmail());

        Optional<Notification> optionalNotification = notificationRepository.findById(message.getNotificationId());

        if(optionalNotification.isEmpty()){
            log.warn("Notification id={} not found in DB, skipping", message.getNotificationId());
            return;
        }

        Notification notification = optionalNotification.get();

        // a redelivery after a crash between the SENT save and the ack must not send twice
        if(notification.getStatus() == NotificationStatus.SENT){
            meterRegistry.counter("worker_duplicate_deliveries_skipped_total").increment();
            log.info("Notification id={} already SENT - skipping duplicate delivery"
                    , notification.getId());
            return;
        }

        notification.setStatus(NotificationStatus.PROCESSING);
        notificationRepository.save(notification);

        try{
            dispatchToProvider(notification, message);

            notification.setStatus(NotificationStatus.SENT);
            notification.setRetryCount(message.getRetryCount());
            notificationRepository.save(notification);

            meterRegistry.counter("notifications_sent_total", "channel"
                    , notification.getChannel().toString()).increment();

            log.info("Notification id={} sent successfully", notification.getId());

        }catch(Exception e) {
            handleFailure(notification, message, e);
        }
    }

    private void dispatchToProvider(Notification notification, NotificationMessage message) throws Exception {

        if(random.nextDouble() < simulateFailureRate){
            throw new RuntimeException("Simulated Provider Failure");
        }

        // get the channel
        NotificationChannel channel = notification.getChannel();

        switch(channel){    // route to proper provider
            case EMAIL -> emailProvider.send(message.getRecipientEmail(), message.getSubject(), message.getMessage());
            case SMS -> smsProvider.send(notification.getRecipientPhone(), message.getMessage());
            case PUSH -> simulatePushSend(notification);
            default -> throw new IllegalArgumentException("Unknown notification channel: " + channel);
        }
    }

    private void simulatePushSend(Notification notification) throws InterruptedException {
        Thread.sleep(pushDelayMs);  // simulate some processing time
        log.info("Simulating push notification sent to device token={} | message='{}'"
                , notification.getRecipientDeviceToken(),
                notification.getMessage());
    }

    private void sendEmail(NotificationMessage message) throws Exception {
        if(random.nextDouble() < simulateFailureRate){
            throw new RuntimeException("Simulated provider failure");
        }

        emailProvider.send(message.getRecipientEmail(), message.getSubject(), message.getMessage());
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

            meterRegistry.counter("notifications_failed_total", "channel"
                    ,notification.getChannel().toString()).increment();
            meterRegistry.counter("dead_letter_messages_total").increment();

            log.error("Notification id={} failed after {} attempts. Sent to DLQ. Error: {}", notification.getId(),
                    nextRetryCount-1, e.getMessage());

            return;
        }

        // attempts remaining - sent to retry queue with delays
        notification.setStatus(NotificationStatus.WAITING_RETRY);  // flips to PROCESSING on next attempt
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

        meterRegistry.counter("retry_attempts_total", "channel"
                ,notification.getChannel().toString()).increment();

        log.warn("Notification id={} failed on attempt {}. Retrying in {}. Error: {}", notification.getId(),
                nextRetryCount, routingKey, e.getMessage());
    }


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
