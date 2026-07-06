package com.yash.Notifyr.repository;

import com.yash.Notifyr.entity.Campaign;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CampaignRepository extends JpaRepository<Campaign, Long> {
}
