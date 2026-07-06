package com.yash.Notifyr.dto;

import com.yash.Notifyr.entity.NotificationChannel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class CampaignRequest {

    @NotBlank(message = "Campaign name is required")
    private String name;

    private String description;

    @NotNull(message = "channel is required")
    private NotificationChannel channel;

    @NotNull(message = "templateId is required")
    private Long templateId;

    @NotEmpty(message = "At least one recipient is required")
    private List<Long> recipientIds;

    private Map<String, String> templateVariables;
}
