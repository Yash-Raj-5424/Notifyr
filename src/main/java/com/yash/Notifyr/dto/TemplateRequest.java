package com.yash.Notifyr.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TemplateRequest {

    @NotBlank(message = "Template name is required")
    private String name;

    @NotBlank(message = "Template subject is required")
    private String subject;

    @NotBlank(message = "Template body is required")
    private String body;
}
