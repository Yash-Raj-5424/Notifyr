package com.yash.Notifyr.provider;

import com.twilio.Twilio;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.type.PhoneNumber;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class TwilioSmsProvider implements SmsProvider {

    @Value("${notification.twilio.account-sid}")
    private String accountSid;

    @Value("${notification.twilio.auth-token}")
    private String authToken;

    @Value("${notification.twilio.from-number}")
    private String fromNumber;

    @PostConstruct
    public void init(){
        Twilio.init(accountSid, authToken);
    }


    @Override
    public void send(String phoneNumber, String message) throws Exception {

        Message.creator(
                new PhoneNumber(phoneNumber),
                new PhoneNumber(fromNumber),
                message
        ).create();

        log.info("SMS sent via Twilio to {}", phoneNumber);
    }
}
