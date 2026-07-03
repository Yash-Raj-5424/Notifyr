package com.yash.Notifyr.repository;

import com.yash.Notifyr.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

}
