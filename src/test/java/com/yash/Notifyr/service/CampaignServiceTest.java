package com.yash.Notifyr.service;

import com.yash.Notifyr.entity.Campaign;
import com.yash.Notifyr.entity.Recipient;
import com.yash.Notifyr.entity.RecipientStatus;
import com.yash.Notifyr.repository.CampaignRepository;
import com.yash.Notifyr.repository.NotificationRepository;
import com.yash.Notifyr.repository.RecipientRepository;
import com.yash.Notifyr.repository.TemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class CampaignServiceTest {

    @Mock
    private CampaignRepository campaignRepository;
    @Mock private TemplateRepository templateRepository;
    @Mock private RecipientRepository recipientRepository;
    @Mock private NotificationRepository notificationRepository;
    @Mock private TemplateService templateService;
    @Mock private RabbitTemplate rabbitTemplate;

    @InjectMocks
    private CampaignService campaignService;

    private Recipient alice, bob, carol, unsubscribedDan;

    @BeforeEach
    void setUp() {
        alice = Recipient.builder()
                .id(1L).name("Alice").email("alice@example.com")
                .tags(List.of("premium")).preferredLanguage("en")
                .status(RecipientStatus.ACTIVE)
                .build();

        bob = Recipient.builder()
                .id(2L).name("Bob").email("bob@example.com")
                .tags(List.of("bangalore")).preferredLanguage("en")
                .status(RecipientStatus.ACTIVE)
                .build();

        carol = Recipient.builder()
                .id(3L).name("Carol").email("carol@example.com")
                .tags(List.of("premium", "bangalore")).preferredLanguage("hi")
                .status(RecipientStatus.ACTIVE)
                .build();

        unsubscribedDan = Recipient.builder()
                .id(4L).name("Dan").email("dan@example.com")
                .tags(List.of("premium")).preferredLanguage("en")
                .status(RecipientStatus.UNSUBSCRIBED)
                .build();
    }

    // Helper to call the private resolveRecipients() method via reflection,
    // since it's intentionally not part of the public API.
    @SuppressWarnings("unchecked")
    private List<Recipient> invokeResolveRecipients(Campaign campaign) throws Exception {
        Method method = CampaignService.class.getDeclaredMethod("resolveRecipients", Campaign.class);
        method.setAccessible(true);
        return (List<Recipient>) method.invoke(campaignService, campaign);
    }

    @Test
    void resolveRecipients_withExplicitIds_returnsOnlyThoseRecipients() throws Exception {
        Campaign campaign = Campaign.builder()
                .recipientIds(List.of(1L, 2L))
                .build();

        when(recipientRepository.findById(1L)).thenReturn(java.util.Optional.of(alice));
        when(recipientRepository.findById(2L)).thenReturn(java.util.Optional.of(bob));

        List<Recipient> result = invokeResolveRecipients(campaign);

        assertThat(result).containsExactlyInAnyOrder(alice, bob);
    }

    @Test
    void resolveRecipients_withTagFilter_matchesAnyTag_OR_logic() throws Exception {
        Campaign campaign = Campaign.builder()
                .recipientIds(List.of())
                .audienceTags(List.of("premium", "bangalore"))
                .build();

        when(recipientRepository.findByStatusNot(RecipientStatus.UNSUBSCRIBED))
                .thenReturn(List.of(alice, bob, carol));

        List<Recipient> result = invokeResolveRecipients(campaign);

        assertThat(result).containsExactlyInAnyOrder(alice, bob, carol);
    }

    @Test
    void resolveRecipients_withTagAndLanguageFilter_appliesAND_logic() throws Exception {
        Campaign campaign = Campaign.builder()
                .recipientIds(List.of())
                .audienceTags(List.of("premium"))
                .audienceLanguage("en")
                .build();

        when(recipientRepository.findByStatusNot(RecipientStatus.UNSUBSCRIBED))
                .thenReturn(List.of(alice, bob, carol));

        List<Recipient> result = invokeResolveRecipients(campaign);

        // Carol has "premium" tag but is "hi" language, so she should be excluded
        assertThat(result).containsExactly(alice);
    }

    @Test
    void resolveRecipients_alwaysExcludesUnsubscribed_evenWithExplicitIds() throws Exception {
        Campaign campaign = Campaign.builder()
                .recipientIds(List.of(4L))
                .build();

        when(recipientRepository.findById(4L)).thenReturn(java.util.Optional.of(unsubscribedDan));

        List<Recipient> result = invokeResolveRecipients(campaign);

        assertThat(result).isEmpty();
    }

    @Test
    void resolveRecipients_withNoAudienceTagsOrLanguage_returnsAllNonUnsubscribed() throws Exception {
        Campaign campaign = Campaign.builder()
                .recipientIds(List.of())
                .build();

        when(recipientRepository.findByStatusNot(RecipientStatus.UNSUBSCRIBED))
                .thenReturn(List.of(alice, bob, carol));

        List<Recipient> result = invokeResolveRecipients(campaign);

        assertThat(result).containsExactlyInAnyOrder(alice, bob, carol);
    }

}
