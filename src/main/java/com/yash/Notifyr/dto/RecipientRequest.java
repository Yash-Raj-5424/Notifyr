package com.yash.Notifyr.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class RecipientRequest {

    @NotBlank(message = "Recipient name is required")
    private String name;

    @NotBlank(message = "Recipient email is required")
    @Email(message = "Invalid email format")
    private String email;

    private String phoneNumber;

    private String deviceToken;

    private String preferredLanguage;

    private String timeZone;

    private List<String> tags;
}
