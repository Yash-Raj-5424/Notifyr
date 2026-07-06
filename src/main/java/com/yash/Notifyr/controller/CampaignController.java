package com.yash.Notifyr.controller;

import com.yash.Notifyr.dto.CampaignRequest;
import com.yash.Notifyr.dto.CampaignResponse;
import com.yash.Notifyr.service.CampaignService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/campaigns")
@RequiredArgsConstructor
public class CampaignController {

    private final CampaignService campaignService;

    @PostMapping
    public ResponseEntity<CampaignResponse> createCampaign(@RequestBody CampaignRequest request) {
        CampaignResponse response = campaignService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<CampaignResponse>> getAll(){
        return ResponseEntity.ok(campaignService.getAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<CampaignResponse> getById(@PathVariable Long id){
        return ResponseEntity.ok(campaignService.getById(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<CampaignResponse> updateCampaign(
            @PathVariable Long id,
            @RequestBody CampaignRequest request){

        return ResponseEntity.ok(campaignService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteCampaign(@PathVariable Long id){
        campaignService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/send")
    public ResponseEntity<CampaignResponse> sendCampaign(@PathVariable Long id){
        CampaignResponse response = campaignService.send(id);
        return ResponseEntity.ok(response);
    }
}
