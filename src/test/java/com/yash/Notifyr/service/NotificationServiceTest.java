package com.yash.Notifyr.service;

import com.yash.Notifyr.dto.NotificationRequest;
import com.yash.Notifyr.dto.NotificationResponse;
import com.yash.Notifyr.entity.Notification;
import com.yash.Notifyr.entity.NotificationStatus;
import com.yash.Notifyr.repository.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class NotificationServiceTest {

    @Mock
    private NotificationRepository notificationRepository;
    @Mock private RabbitTemplate rabbitTemplate;
    @Mock private RedisTemplate<String, NotificationResponse> redisTemplate;
    @Mock private ValueOperations<String, NotificationResponse> valueOperations;

    @InjectMocks
    private NotificationService notificationService;

    private NotificationRequest request;

    @BeforeEach
    void setUp(){
        request = new NotificationRequest();
        request.setRecipientEmail("test@example.com");
        request.setSubject("Test Subject");
        request.setMessage("Test Message");

        ReflectionTestUtils.setField(notificationService, "exchangeName", "notification.exchange");
        ReflectionTestUtils.setField(notificationService, "routingKey", "notification.route");
    }

    @Test
    void sendNotification_withCachedIdempotencyKey_returnsCachedResponseWithoutSavingNew(){
        NotificationResponse cachedResponse = new NotificationResponse();
        cachedResponse.setId(99L);
        cachedResponse.setStatus(NotificationStatus.SENT);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("idempotency:abc-123")).thenReturn(cachedResponse);

        NotificationResponse result = notificationService.sendNotification(request, "abc-123");

        assertThat(result.getId()).isEqualTo(99L);
        assertThat(result.getStatus()).isEqualTo(NotificationStatus.SENT);
        verify(notificationRepository, never()).save(any());
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
    }

    @Test
    void sendNotification_withNewIdempotencyKey_createsAndCachesNewNotification(){
        Notification saved = Notification.builder()
                .id(1L)
                .recipientEmail("test@example.com")
                .subject("Test Subject")
                .message("Test Message")
                .status(NotificationStatus.QUEUED)
                .build();

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("idempotency:new-key")).thenReturn(null);
        when(notificationRepository.save(any(Notification.class))).thenReturn(saved);

        NotificationResponse result = notificationService.sendNotification(request, "new-key");

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getStatus()).isEqualTo(NotificationStatus.QUEUED);
        verify(rabbitTemplate).convertAndSend(eq("notification.exchange"), eq("notification.route"), any(Object.class));
        verify(valueOperations).set(eq("idempotency:new-key"), any(NotificationResponse.class), any());
    }

    @Test
    void sendNotification_withoutIdempotencyKey_skipsRedisEntirely(){
        Notification saved = Notification.builder()
                .id(2L)
                .recipientEmail("test@example.com")
                .subject("Test Subject")
                .message("Test Message")
                .status(NotificationStatus.QUEUED)
                .build();

        when(notificationRepository.save(any(Notification.class))).thenReturn(saved);

        NotificationResponse result = notificationService.sendNotification(request, null);

        assertThat(result.getId()).isEqualTo(2L);
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    void sendNotification_whenPublishFails_marksNotificationAsFailed(){
        Notification saved = Notification.builder()
                .id(3L)
                .recipientEmail("test@example.com")
                .subject("Test Subject")
                .message("Test Message")
                .status(NotificationStatus.QUEUED)
                .build();

        when(notificationRepository.save(any(Notification.class))).thenReturn(saved);
        doThrow(new AmqpException("connection refused"))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class));

        NotificationResponse result = notificationService.sendNotification(request, null);

        assertThat(result.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(result.getFailureReason()).contains("connection refused");
        verify(notificationRepository, times(2)).save(any(Notification.class)); // once QUEUED, once FAILED
    }

}
