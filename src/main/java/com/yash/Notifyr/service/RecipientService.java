package com.yash.Notifyr.service;

import com.yash.Notifyr.dto.RecipientRequest;
import com.yash.Notifyr.dto.RecipientResponse;
import com.yash.Notifyr.entity.Recipient;
import com.yash.Notifyr.entity.RecipientStatus;
import com.yash.Notifyr.exception.DuplicateEmailException;
import com.yash.Notifyr.exception.RecipientNotFoundException;
import com.yash.Notifyr.repository.RecipientRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class RecipientService {

    private final RecipientRepository recipientRepository;

    public RecipientResponse create(RecipientRequest request){

        recipientRepository.findByEmail(request.getEmail()).ifPresent(recipient -> {
            throw new DuplicateEmailException(request.getEmail());
        });

        Recipient recipient = Recipient.builder()
                .name(request.getName())
                .email(request.getEmail())
                .phoneNumber(request.getPhoneNumber())
                .deviceToken(request.getDeviceToken())
                .preferredLanguage(request.getPreferredLanguage())
                .timeZone(request.getTimeZone())
                .tags(request.getTags())
                .build();

        recipient = recipientRepository.save(recipient);
        return mapToResponse(recipient);
    }

    public RecipientResponse getById(Long id){
        Recipient recipient = recipientRepository.findById(id)
                .orElseThrow(() -> new RecipientNotFoundException(id));
        return mapToResponse(recipient);
    }

    public List<RecipientResponse>  getAll(){
        return recipientRepository.findAll()
                .stream()
                .map(this::mapToResponse)
                .toList();
    }

    public List<RecipientResponse> search(String keyword){
        return recipientRepository.searchByNameOrEmail(keyword)
                .stream()
                .map(this::mapToResponse)
                .toList();
    }

    public RecipientResponse update(Long id, RecipientRequest request){

        Recipient recipient = recipientRepository.findById(id)
                .orElseThrow(() -> new RecipientNotFoundException(id));

        // the new email should not be already taken
        if(!recipient.getEmail().equals(request.getEmail())) {
            recipientRepository.findByEmail(request.getEmail()).ifPresent(existingRecipient -> {
                throw new DuplicateEmailException(request.getEmail());
            });
        }

        recipient.setName(request.getName());
        recipient.setEmail(request.getEmail());
        recipient.setPhoneNumber(request.getPhoneNumber());
        recipient.setDeviceToken(request.getDeviceToken());
        recipient.setPreferredLanguage(request.getPreferredLanguage());
        recipient.setTimeZone(request.getTimeZone());
        recipient.setTags(request.getTags());

        recipient = recipientRepository.save(recipient);
        return mapToResponse(recipient);
    }

    public void delete(Long id){
        Recipient recipient = recipientRepository.findById(id)
                .orElseThrow(() -> new RecipientNotFoundException(id));

        recipientRepository.delete(recipient);
    }

    // update status
    public RecipientResponse updateStatus(Long id, RecipientStatus status){
        Recipient recipient = recipientRepository.findById(id)
                .orElseThrow(() -> new RecipientNotFoundException(id));

        recipient.setStatus(status);
        recipient = recipientRepository.save(recipient);
        return mapToResponse(recipient);
    }

    private RecipientResponse mapToResponse(Recipient recipient) {

        return new RecipientResponse(
                recipient.getId(),
                recipient.getName(),
                recipient.getEmail(),
                recipient.getPhoneNumber(),
                recipient.getDeviceToken(),
                recipient.getPreferredLanguage(),
                recipient.getTimeZone(),
                recipient.getTags(),
                recipient.getStatus(),
                recipient.getCreatedAt(),
                recipient.getUpdatedAt()
        );
    }
}
