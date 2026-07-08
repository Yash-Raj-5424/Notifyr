package com.yash.Notifyr.repository;

import com.yash.Notifyr.entity.Campaign;
import com.yash.Notifyr.entity.CampaignStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface CampaignRepository extends JpaRepository<Campaign, Long> {

    List<Campaign> findByStatusAndScheduledTimeLessThanEqual(CampaignStatus status, LocalDateTime scheduledTime);
}
