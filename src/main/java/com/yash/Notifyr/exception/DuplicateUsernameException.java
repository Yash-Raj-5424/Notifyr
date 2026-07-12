package com.yash.Notifyr.exception;

import jakarta.validation.constraints.NotBlank;

public class DuplicateUsernameException extends RuntimeException {
    public DuplicateUsernameException(String username){
        super("Username '" + username + "' already exists");
    }
}
