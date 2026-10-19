package com.yash.Notifyr.exception;

import java.util.List;

public class TemplateValidationException extends RuntimeException {
    public TemplateValidationException(List<String> missingVariables) {
        super("Missing template variables: " + missingVariables);
    }
}
