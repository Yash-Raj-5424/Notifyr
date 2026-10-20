package com.yash.Notifyr.service;

import com.yash.Notifyr.dto.NotificationMessage;
import com.yash.Notifyr.dto.NotificationRequest;
import com.yash.Notifyr.dto.NotificationResponse;
import com.yash.Notifyr.entity.Notification;
import com.yash.Notifyr.entity.NotificationStatus;
import com.yash.Notifyr.exception.IdempotencyInProgressException;
import com.yash.Notifyr.exception.ServiceUnavailableException;
import com.yash.Notifyr.repository.NotificationRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class NotificationServiceTest {

    @Mock
    private NotificationRepository notificationRepository;
    @Mock private NotificationPublisher notificationPublisher;
    @Mock private RedisTemplate<String, NotificationResponse> redisTemplate;
    @Mock private ValueOperations<String, NotificationResponse> valueOperations;
    @Mock private MeterRegistry meterRegistry;
    @Mock private Counter counter;

    @InjectMocks
    private NotificationService notificationService;

    private NotificationRequest request;

    @BeforeEach
    void setUp(){
        request = new NotificationRequest();
        request.setRecipientEmail("test@example.com");
        request.setSubject("Test Subject");
        request.setMessage("Test Message");

        ReflectionTestUtils.setField(notificationService, "failOpen", true);
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
        verify(notificationPublisher, never()).publishWithRetryFallback(any(), any());
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
        when(valueOperations.setIfAbsent(eq("idempotency:new-key"), any(NotificationResponse.class)
                , any(Duration.class))).thenReturn(true);
        when(notificationRepository.save(any(Notification.class))).thenReturn(saved);
        when(notificationPublisher.publishWithRetryFallback(any(Notification.class)
                , any(NotificationMessage.class))).thenReturn(true);

        NotificationResponse result = notificationService.sendNotification(request, "new-key");

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getStatus()).isEqualTo(NotificationStatus.QUEUED);
        verify(notificationPublisher).publishWithRetryFallback(any(Notification.class)
                , any(NotificationMessage.class));
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
    void sendNotification_whenPublishFails_schedulesRetryAndReturnsWaitingRetry(){
        Notification saved = Notification.builder()
                .id(3L)
                .recipientEmail("test@example.com")
                .subject("Test Subject")
                .message("Test Message")
                .status(NotificationStatus.QUEUED)
                .build();

        when(notificationRepository.save(any(Notification.class))).thenReturn(saved);
        doAnswer(invocation -> {
            Notification notification = invocation.getArgument(0);
            notification.setStatus(NotificationStatus.WAITING_RETRY);
            notification.setFailureReason("Failed to publish to queue: connection refused");
            return true;
        }).when(notificationPublisher).publishWithRetryFallback(any(Notification.class)
                , any(NotificationMessage.class));

        NotificationResponse result = notificationService.sendNotification(request, null);

        assertThat(result.getStatus()).isEqualTo(NotificationStatus.WAITING_RETRY);
        assertThat(result.getFailureReason()).contains("connection refused");
        verify(notificationRepository, times(1)).save(any(Notification.class)); // QUEUED only - publisher owns the retry-path saves
    }

    @Test
    void sendNotification_whenBothPublishesFail_returnsFailed(){
        Notification saved = Notification.builder()
                .id(4L)
                .recipientEmail("test@example.com")
                .subject("Test Subject")
                .message("Test Message")
                .status(NotificationStatus.QUEUED)
                .build();

        when(notificationRepository.save(any(Notification.class))).thenReturn(saved);
        doAnswer(invocation -> {
            Notification notification = invocation.getArgument(0);
            notification.setStatus(NotificationStatus.FAILED);
            notification.setFailureReason("Failed to publish to queue (both attempts): connection refused");
            return false;
        }).when(notificationPublisher).publishWithRetryFallback(any(Notification.class)
                , any(NotificationMessage.class));

        NotificationResponse result = notificationService.sendNotification(request, null);

        assertThat(result.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(result.getFailureReason()).contains("connection refused");
        verify(notificationRepository, times(1)).save(any(Notification.class));
    }

    @Test
    void sendNotification_whenRedisGetFails_failOpenContinuesAndSends(){
        Notification saved = Notification.builder()
                .id(5L)
                .recipientEmail("test@example.com")
                .subject("Test Subject")
                .message("Test Message")
                .status(NotificationStatus.QUEUED)
                .build();

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("idempotency:down-key"))
                .thenThrow(new RedisConnectionFailureException("connection refused"));
        when(notificationRepository.save(any(Notification.class))).thenReturn(saved);
        when(notificationPublisher.publishWithRetryFallback(any(Notification.class)
                , any(NotificationMessage.class))).thenReturn(true);
        when(meterRegistry.counter("idempotency_failures_total", "op", "get")).thenReturn(counter);

        NotificationResponse result = notificationService.sendNotification(request, "down-key");

        assertThat(result.getId()).isEqualTo(5L);
        assertThat(result.getStatus()).isEqualTo(NotificationStatus.QUEUED);
        verify(counter).increment();
        verify(notificationRepository).save(any(Notification.class));
        verify(notificationPublisher).publishWithRetryFallback(any(Notification.class)
                , any(NotificationMessage.class));
    }

    @Test
    void sendNotification_whenRedisGetFails_failClosedRejects(){
        ReflectionTestUtils.setField(notificationService, "failOpen", false);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("idempotency:down-key"))
                .thenThrow(new RedisConnectionFailureException("connection refused"));
        when(meterRegistry.counter("idempotency_failures_total", "op", "get")).thenReturn(counter);

        assertThatThrownBy(() -> notificationService.sendNotification(request, "down-key"))
                .isInstanceOf(ServiceUnavailableException.class);

        verify(counter).increment();
        verify(notificationRepository, never()).save(any());
        verify(notificationPublisher, never()).publishWithRetryFallback(any(), any());
    }

    @Test
    void sendNotification_whenRedisSetFails_returnsResponseNormally(){
        Notification saved = Notification.builder()
                .id(6L)
                .recipientEmail("test@example.com")
                .subject("Test Subject")
                .message("Test Message")
                .status(NotificationStatus.QUEUED)
                .build();

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("idempotency:set-down")).thenReturn(null);
        when(valueOperations.setIfAbsent(eq("idempotency:set-down"), any(NotificationResponse.class)
                , any(Duration.class))).thenReturn(true);
        when(notificationRepository.save(any(Notification.class))).thenReturn(saved);
        when(notificationPublisher.publishWithRetryFallback(any(Notification.class)
                , any(NotificationMessage.class))).thenReturn(true);
        doThrow(new RedisConnectionFailureException("connection refused"))
                .when(valueOperations).set(eq("idempotency:set-down"), any(NotificationResponse.class), any());
        when(meterRegistry.counter("idempotency_failures_total", "op", "set")).thenReturn(counter);

        NotificationResponse result = notificationService.sendNotification(request, "set-down");

        assertThat(result.getId()).isEqualTo(6L);
        assertThat(result.getStatus()).isEqualTo(NotificationStatus.QUEUED);
        verify(counter).increment();
    }

    @Test
    void sendNotification_whenKeyAlreadyInProgress_throwsConflict(){
        NotificationResponse marker = new NotificationResponse(); // id null = in-progress marker

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("idempotency:in-flight")).thenReturn(marker);
        when(meterRegistry.counter("idempotency_conflicts_total")).thenReturn(counter);

        assertThatThrownBy(() -> notificationService.sendNotification(request, "in-flight"))
                .isInstanceOf(IdempotencyInProgressException.class);

        verify(counter).increment();
        verify(notificationRepository, never()).save(any());
        verify(notificationPublisher, never()).publishWithRetryFallback(any(), any());
    }

    @Test
    void sendNotification_whenConcurrentRequestCompletes_returnsItsResponse(){
        NotificationResponse winnerResponse = new NotificationResponse();
        winnerResponse.setId(42L);
        winnerResponse.setStatus(NotificationStatus.QUEUED);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("idempotency:race-key")).thenReturn(null, winnerResponse);
        when(valueOperations.setIfAbsent(eq("idempotency:race-key"), any(NotificationResponse.class)
                , any(Duration.class))).thenReturn(false);

        NotificationResponse result = notificationService.sendNotification(request, "race-key");

        assertThat(result.getId()).isEqualTo(42L);
        verify(notificationRepository, never()).save(any());
        verify(notificationPublisher, never()).publishWithRetryFallback(any(), any());
    }

    @Test
    void sendNotification_whenConcurrentRequestStillRunning_throwsConflict(){
        NotificationResponse marker = new NotificationResponse(); // id null = in-progress marker

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("idempotency:race-key")).thenReturn(null, marker);
        when(valueOperations.setIfAbsent(eq("idempotency:race-key"), any(NotificationResponse.class)
                , any(Duration.class))).thenReturn(false);
        when(meterRegistry.counter("idempotency_conflicts_total")).thenReturn(counter);

        assertThatThrownBy(() -> notificationService.sendNotification(request, "race-key"))
                .isInstanceOf(IdempotencyInProgressException.class);

        verify(counter).increment();
        verify(notificationRepository, never()).save(any());
    }

}
