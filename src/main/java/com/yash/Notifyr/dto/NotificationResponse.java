package com.yash.Notifyr.dto;

import com.yash.Notifyr.entity.NotificationStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class NotificationResponse {

    private Long id;
    private String recipientEmail;
    private NotificationStatus status;
}
