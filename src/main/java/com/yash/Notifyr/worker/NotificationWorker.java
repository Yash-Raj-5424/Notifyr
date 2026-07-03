package com.yash.Notifyr.worker;

import com.yash.Notifyr.dto.NotificationMessage;
import com.yash.Notifyr.entity.Notification;
import com.yash.Notifyr.entity.NotificationStatus;
import com.yash.Notifyr.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationWorker {

    private final NotificationRepository notificationRepository;

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
            notificationRepository.save(notification);

            log.info("Notification id={} sent successfully", notification.getId());
        }catch(Exception e){
            notification.setStatus(NotificationStatus.FAILED);
            notification.setFailureReason(e.getMessage());
            notificationRepository.save(notification);

            log.error("Notification id={} failed: {}", notification.getId(), e.getMessage());
        }
    }

    private void simulateSend(NotificationMessage message) throws InterruptedException {

        Thread.sleep(1000);

        log.info("Simulating email sent to {} subject='{}' body='{}'", message.getRecipientEmail(),
                message.getSubject(), message.getMessage());
    }

}
