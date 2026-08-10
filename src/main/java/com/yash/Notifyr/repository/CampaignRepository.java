package com.yash.Notifyr.repository;

import com.yash.Notifyr.dto.CampaignSummary;
import com.yash.Notifyr.entity.Campaign;
import com.yash.Notifyr.entity.CampaignStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;

public interface CampaignRepository extends JpaRepository<Campaign, Long> {

    List<Campaign> findByStatusAndScheduledTimeLessThanEqual(CampaignStatus status, LocalDateTime scheduledTime);

    @Query("""
            select new com.yash.Notifyr.dto.CampaignSummary(
                c.id, c.name, c.description, c.channel, c.templateId,
                c.audienceLanguage, c.status, c.createdAt, c.updatedAt, c.scheduledTime)
            from Campaign c
            """)
    List<CampaignSummary> findAllSummaries();
}
