package com.yash.Notifyr.provider;

public interface EmailProvider {

    void send(String to, String subject, String body) throws Exception;
}
