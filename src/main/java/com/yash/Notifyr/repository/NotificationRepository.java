package com.yash.Notifyr.repository;

import com.yash.Notifyr.entity.Notification;
import com.yash.Notifyr.entity.NotificationStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findByStatusInAndUpdatedAtBefore(
            Collection<NotificationStatus> statuses,
            LocalDateTime cutoff,
            Pageable pageable
    );
}
