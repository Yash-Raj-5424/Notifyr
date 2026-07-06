package com.yash.Notifyr.service;

import com.yash.Notifyr.dto.CampaignRequest;
import com.yash.Notifyr.dto.CampaignResponse;
import com.yash.Notifyr.dto.NotificationMessage;
import com.yash.Notifyr.entity.*;
import com.yash.Notifyr.exception.CampaignNotFoundException;
import com.yash.Notifyr.exception.RecipientNotFoundException;
import com.yash.Notifyr.exception.TemplateNotFoundException;
import com.yash.Notifyr.exception.UnsupportedChannelException;
import com.yash.Notifyr.repository.CampaignRepository;
import com.yash.Notifyr.repository.NotificationRepository;
import com.yash.Notifyr.repository.RecipientRepository;
import com.yash.Notifyr.repository.TemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class CampaignService {

    private final CampaignRepository campaignRepository;
    private final TemplateRepository templaterepository;
    private final RecipientRepository recipientRepository;
    private final NotificationRepository notificationRepository;
    private final TemplateService templateService;
    private final RabbitTemplate rabbitTemplate;

    @Value("${notification.exchange.name}")
    private String exchangeName;

    @Value("${notification.routing-key}")
    private String routingKey;

    public CampaignResponse create(CampaignRequest request){

        templaterepository.findById(request.getTemplateId())
                .orElseThrow(() -> new TemplateNotFoundException(request.getTemplateId()));

        for(Long recipientId : request.getRecipientIds()){
            recipientRepository.findById(recipientId)
                    .orElseThrow(() -> new RecipientNotFoundException(recipientId));
        }

        Campaign campaign = Campaign.builder()
                .name(request.getName())
                .description(request.getDescription())
                .channel(request.getChannel())
                .templateId(request.getTemplateId())
                .recipientIds(request.getRecipientIds())
                .templateVariables(
                        request.getTemplateVariables() != null ?
                        request.getTemplateVariables() : new HashMap<>())
                .status(CampaignStatus.DRAFT)
                .build();

        campaign = campaignRepository.save(campaign);
        return mapToResponse(campaign);
    }

    public CampaignResponse getById(Long id) {
        Campaign campaign = campaignRepository.findById(id)
                .orElseThrow(() -> new CampaignNotFoundException(id));
        return mapToResponse(campaign);
    }

    public List<CampaignResponse> getAll(){
        return campaignRepository.findAll()
                .stream()
                .map(this::mapToResponse)
                .toList();
    }

    public CampaignResponse update(Long id, CampaignRequest request){

        Campaign campaign = campaignRepository.findById(id)
                .orElseThrow(() -> new CampaignNotFoundException(id));

        campaign.setName(request.getName());
        campaign.setDescription(request.getDescription());
        campaign.setChannel(request.getChannel());
        campaign.setTemplateId(request.getTemplateId());
        campaign.setRecipientIds(request.getRecipientIds());
        campaign.setTemplateVariables(
                request.getTemplateVariables() != null ?
                request.getTemplateVariables() : new HashMap<>());

        campaign = campaignRepository.save(campaign);
        return mapToResponse(campaign);
    }

    public void delete(Long id){
        Campaign campaign = campaignRepository.findById(id)
                .orElseThrow(() -> new CampaignNotFoundException(id));
        campaignRepository.delete(campaign);
    }

    public CampaignResponse send(Long campaignId){

        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new CampaignNotFoundException(campaignId));

        if(campaign.getChannel() != NotificationChannel.EMAIL){
            throw new UnsupportedChannelException(campaign.getChannel());
        }

        // fetch template for this campaign
        Template template = templaterepository.findById(campaign.getTemplateId())
                .orElseThrow(() -> new TemplateNotFoundException(campaign.getTemplateId()));

        campaign.setStatus(CampaignStatus.RUNNING);
        campaignRepository.save(campaign);

        // send notifications to all recipients
        for(Long recipientId : campaign.getRecipientIds()) {

            Recipient recipient = recipientRepository.findById(recipientId)
                    .orElseThrow(() -> new RecipientNotFoundException(recipientId));

            Map<String, String> variables = new HashMap<>(campaign.getTemplateVariables());
            variables.put("name", recipient.getName());
            variables.put("email", recipient.getEmail());

            String renderedSubject = templateService.render(template.getSubject(), variables);
            String renderedBody = templateService.render(template.getBody(), variables);

            Notification notification = Notification.builder()
                    .recipientEmail(recipient.getEmail())
                    .subject(renderedSubject)
                    .message(renderedBody)
                    .status(NotificationStatus.QUEUED)
                    .build();

            notification = notificationRepository.save(notification);

            NotificationMessage message = new NotificationMessage(
                    notification.getId(),
                    notification.getRecipientEmail(),
                    notification.getSubject(),
                    notification.getMessage(),
                    0
            );

            rabbitTemplate.convertAndSend(exchangeName, routingKey, message);

            log.info("Campaign {} : queued notification {} for recipient {}"
                    ,campaignId, notification.getId(), recipient.getEmail());

        }

        campaign.setStatus(CampaignStatus.COMPLETED);
        campaignRepository.save(campaign);

        log.info("Campaign {} : completed : {} notifications queued"
                ,campaign.getId(), campaign.getRecipientIds().size());

        return mapToResponse(campaign);
    }

    private CampaignResponse mapToResponse(Campaign campaign) {
        return new CampaignResponse(
                campaign.getId(),
                campaign.getName(),
                campaign.getDescription(),
                campaign.getChannel(),
                campaign.getTemplateId(),
                campaign.getRecipientIds(),
                campaign.getTemplateVariables(),
                campaign.getStatus(),
                campaign.getCreatedAt(),
                campaign.getUpdatedAt()
        );
    }

}
