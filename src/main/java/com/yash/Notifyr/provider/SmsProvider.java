package com.yash.Notifyr.provider;

public interface SmsProvider {

    void send(String phoneNumber, String message) throws Exception;
}
