package com.yash.Notifyr.service;

import com.yash.Notifyr.dto.NotificationMessage;
import com.yash.Notifyr.dto.NotificationRequest;
import com.yash.Notifyr.entity.Notification;
import com.yash.Notifyr.entity.NotificationStatus;
import com.yash.Notifyr.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final RabbitTemplate rabbitTemplate;

    @Value("${notification.exchange.name}")
    private String exchangeName;

    @Value("${notification.routing-key}")
    private String routingKey;

    public Notification sendNotification(NotificationRequest request){

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
                notification.getMessage()
        );

        // publish to rabbitmq
        rabbitTemplate.convertAndSend(exchangeName, routingKey, message);

        log.info("Notification {} queued for recipient {}", notification.getId(), notification.getRecipientEmail());

        return notification;
    }
}
