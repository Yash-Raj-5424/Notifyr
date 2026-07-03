package com.yash.Notifyr.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class NotificationRequest {

    @NotBlank(message = "recipientEmail is required")
    @Email(message = "recipientEmail must be a valid email address")
    private String recipientEmail;

    @NotBlank(message = "subject is required")
    private String subject;

    @NotBlank(message = "message is required")
    private String message;
}
