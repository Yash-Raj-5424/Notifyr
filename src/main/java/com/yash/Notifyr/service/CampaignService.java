package com.yash.Notifyr.service;

import com.yash.Notifyr.dto.CampaignDispatchMessage;
import com.yash.Notifyr.dto.CampaignRequest;
import com.yash.Notifyr.dto.CampaignResponse;
import com.yash.Notifyr.dto.NotificationMessage;
import com.yash.Notifyr.entity.*;
import com.yash.Notifyr.exception.*;
import com.yash.Notifyr.repository.CampaignRepository;
import com.yash.Notifyr.repository.NotificationRepository;
import com.yash.Notifyr.repository.RecipientRepository;
import com.yash.Notifyr.repository.TemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    @Value("${notification.campaign-dispatch.exchange}")
    private String campaignDispatchExchangeName;

    @Value("${notification.campaign-dispatch.queue}")
    private String campaignDispatchQueueName;

    @Value("${notification.campaign-dispatch.routing-key}")
    private String campaignDispatchRoutingKey;

    public CampaignResponse create(CampaignRequest request) {

        templaterepository.findById(request.getTemplateId())
                .orElseThrow(() -> new TemplateNotFoundException(request.getTemplateId()));

        validateTargeting(request); // validate targeting criteria

        CampaignStatus initialStatus = CampaignStatus.DRAFT;

        if(request.getScheduledTime() != null){
            if(request.getScheduledTime().isBefore(java.time.LocalDateTime.now())){
                throw new InvalidScheduleTimeException();
            }
            initialStatus = CampaignStatus.SCHEDULED;
        }

        if (request.getRecipientIds() != null) {
            for (Long recipientId : request.getRecipientIds()) {
                recipientRepository.findById(recipientId)
                        .orElseThrow(() -> new RecipientNotFoundException(recipientId));
            }
        }

        Campaign campaign = Campaign.builder()
                .name(request.getName())
                .description(request.getDescription())
                .channel(request.getChannel())
                .templateId(request.getTemplateId())
                .recipientIds(request.getRecipientIds() != null ? request.getRecipientIds() : List.of())
                .audienceTags(request.getAudienceTags() != null ? request.getAudienceTags() : List.of())
                .templateVariables(request.getTemplateVariables() != null
                        ? request.getTemplateVariables() : new HashMap<>())
                .scheduledTime(request.getScheduledTime())
                .status(initialStatus)
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

        validateTargeting(request);

        campaign.setName(request.getName());
        campaign.setDescription(request.getDescription());
        campaign.setChannel(request.getChannel());
        campaign.setTemplateId(request.getTemplateId());
        campaign.setRecipientIds(request.getRecipientIds() != null ? request.getRecipientIds() : List.of());
        campaign.setAudienceTags(request.getAudienceTags() != null ? request.getAudienceTags() : List.of());
        campaign.setAudienceLanguage(request.getAudienceLanguage());
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

    // validates and triggers the dispatch
    public CampaignResponse send(Long campaignId){

        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new CampaignNotFoundException(campaignId));

        if(campaign.getStatus() != CampaignStatus.DRAFT && campaign.getStatus() != CampaignStatus.SCHEDULED){
            throw new CampaignAlreadySentException(campaign.getId(), campaign.getStatus());
        }

        if(campaign.getChannel() != NotificationChannel.EMAIL){
            throw new UnsupportedChannelException(campaign.getChannel());
        }

        campaign.setStatus(CampaignStatus.RUNNING);
        campaignRepository.save(campaign);

        CampaignDispatchMessage dispatchMessage = new CampaignDispatchMessage(campaign.getId());
        rabbitTemplate.convertAndSend(campaignDispatchExchangeName, campaignDispatchRoutingKey, dispatchMessage);

        log.info("Campaign {} : dispatch triggered, message published to {}"
                ,campaign.getId(), campaignDispatchQueueName);

        return mapToResponse(campaign);
    }

    // called by dispatch worker - handles the actual fan-out
    @Transactional
    public void processDispatch(Long campaignId){

        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new CampaignNotFoundException(campaignId));

        // fetch template for this campaign
        try {
            Template template = templaterepository.findById(campaign.getTemplateId())
                    .orElseThrow(() -> new TemplateNotFoundException(campaign.getTemplateId()));

            // find all recipients based on targeting criteria
            List<Recipient> targetRecipients = resolveRecipients(campaign);

            int failedToQueue = 0;

            // send notifications to all target recipients
            for (Recipient recipient : targetRecipients) {

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

                try {
                    rabbitTemplate.convertAndSend(exchangeName, routingKey, message);

                    log.info("Campaign {} : queued notification {} for recipient {}"
                            , campaignId, notification.getId(), recipient.getEmail());
                } catch (Exception e) {
                    notification.setStatus(NotificationStatus.FAILED);
                    notification.setFailureReason("Failed to publish to queue: " + e.getMessage());
                    notificationRepository.save(notification);
                    failedToQueue++;
                    log.error("Campaign {} : failed to queue notification {} for recipient {}: {}",
                            campaign.getId(), notification.getId(), recipient.getEmail(), e.getMessage());
                }

            }

            campaign.setStatus(failedToQueue == 0 ? CampaignStatus.COMPLETED : CampaignStatus.FAILED);
            campaignRepository.save(campaign);

            log.info("Campaign {} : finished - {} queued, {} failed to queue"
                    , campaign.getId(), targetRecipients.size() - failedToQueue, failedToQueue);
        }catch(Exception e){
            campaign.setStatus(CampaignStatus.FAILED);
            campaignRepository.save(campaign);
            log.error("Campaign {} : dispatch failed due to error: {}", campaign.getId(), e.getMessage());
            throw e;
        }
    }

    private List<Recipient> resolveRecipients(Campaign campaign) {

        List<Recipient> candidates;
        boolean hasExplicitIds = campaign.getRecipientIds() != null
                && !campaign.getRecipientIds().isEmpty();

        if(hasExplicitIds){
            candidates = campaign.getRecipientIds().stream()
                    .map(id -> recipientRepository.findById(id)
                            .orElseThrow(() -> new RecipientNotFoundException(id)))
                    .toList();
        }else{
            candidates = recipientRepository.findByStatusNot(RecipientStatus.UNSUBSCRIBED);

            boolean hasTags = campaign.getAudienceTags() != null
                    && !campaign.getAudienceTags().isEmpty();
            boolean hasLanguages = campaign.getAudienceLanguage() != null
                    && !campaign.getAudienceLanguage().isEmpty();

            if(hasTags){
                candidates = candidates.stream()
                        .filter(r -> r.getTags() != null && !r.getTags().isEmpty())
                        .filter(r -> r.getTags().stream().anyMatch(campaign.getAudienceTags()::contains))
                        .toList();
            }
            if(hasLanguages){
                candidates = candidates.stream()
                        .filter(r -> campaign.getAudienceLanguage().equals(r.getPreferredLanguage()))
                        .toList();
            }
        }

        // exclude unsubscribed recipients always
        return candidates.stream()
                .filter(r -> r.getStatus() != RecipientStatus.UNSUBSCRIBED)
                .toList();
    }

    private void validateTargeting(CampaignRequest request) {
        boolean hasRecipientIds = request.getRecipientIds() != null && !request.getRecipientIds().isEmpty();
        boolean hasAudienceTags = request.getAudienceTags() != null && !request.getAudienceTags().isEmpty();
        boolean hasAudienceLanguage = request.getAudienceLanguage() != null
                && !request.getAudienceLanguage().isEmpty();

        if (!hasRecipientIds && !hasAudienceTags && !hasAudienceLanguage) {
            throw new InvalidCampaignTargetException();
        }
    }

    private CampaignResponse mapToResponse(Campaign campaign) {
        return new CampaignResponse(
                campaign.getId(),
                campaign.getName(),
                campaign.getDescription(),
                campaign.getChannel(),
                campaign.getTemplateId(),
                campaign.getRecipientIds(),
                campaign.getAudienceTags(),
                campaign.getAudienceLanguage(),
                campaign.getTemplateVariables(),
                campaign.getStatus(),
                campaign.getCreatedAt(),
                campaign.getUpdatedAt(),
                campaign.getScheduledTime()
        );
    }

}
