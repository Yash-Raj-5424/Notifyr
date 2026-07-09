package com.yash.Notifyr.worker;

import com.yash.Notifyr.dto.CampaignDispatchMessage;
import com.yash.Notifyr.service.CampaignService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class CampaignDispatchWorker {

    private final CampaignService campaignService;

    @RabbitListener(queues = "${notification.campaign-dispatch.queue}")
    public void handleCampaignDispatch(CampaignDispatchMessage message){

        log.info("Received dispatch trigger for campaign {}", message.getCampaignId());

        try{
            campaignService.processDispatch(message.getCampaignId());
        }catch(Exception e){
            log.error("Failed to process dispatch for campaign {}: {}", message.getCampaignId(), e.getMessage());
        }
    }
}
