package com.yash.Notifyr.service;

import com.yash.Notifyr.dto.TemplateRequest;
import com.yash.Notifyr.dto.TemplateResponse;
import com.yash.Notifyr.entity.Template;
import com.yash.Notifyr.exception.DuplicateTemplateNameException;
import com.yash.Notifyr.exception.TemplateNotFoundException;
import com.yash.Notifyr.repository.TemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TemplateService {

    private final TemplateRepository templateRepository;
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{(.+?)}}");

    public TemplateResponse create(TemplateRequest request){

        templateRepository.findByName(request.getName()).ifPresent(template -> {
            throw new TemplateNotFoundException("Template with name " + request.getName() + " already exists");
        });

        Template template = Template.builder()
                .name(request.getName())
                .subject(request.getSubject())
                .body(request.getBody())
                .build();

        template = templateRepository.save(template);
        return mapToResponse(template);
    }

    public TemplateResponse getById(Long id){
        Template template = templateRepository.findById(id)
                .orElseThrow(() -> new TemplateNotFoundException("Template with id " + id + " not found"));
        return mapToResponse(template);
    }

    public List<TemplateResponse> getAll(){
        List<Template> templates = templateRepository.findAll();

        return templates.stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public TemplateResponse update(Long id, TemplateRequest request){

        Template template = templateRepository.findById(id)
                .orElseThrow(() -> new TemplateNotFoundException("Template with id " + id + " not found"));

        // new template name mustn't be already taken
        if(!template.getName().equals(request.getName())) {
            templateRepository.findByName(request.getName()).ifPresent(existingTemplate -> {
                throw new DuplicateTemplateNameException("Template with name " + request.getName() + " already exists");
            });
        }

        template.setName(request.getName());
        template.setSubject(request.getSubject());
        template.setBody(request.getBody());

        template = templateRepository.save(template);
        return mapToResponse(template);
    }

    public void delete(Long id){
        Template template = templateRepository.findById(id)
                .orElseThrow(() -> new TemplateNotFoundException("Template with id " + id + " not found"));
        templateRepository.delete(template);
    }

    public String render(String text, Map<String, String> variables){

        if(text == null)  return null;

        Matcher matcher = PLACEHOLDER_PATTERN.matcher(text);
        StringBuilder result = new StringBuilder();

        while(matcher.find()){
            String key = matcher.group(1).trim();
            String value = variables != null ? variables.get(key) : null;
            String replacement = value != null ? value : matcher.group(0); // keep as is if val is null
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);

        return result.toString();
    }
    private TemplateResponse mapToResponse(Template template) {
        return new TemplateResponse(
                template.getId(),
                template.getName(),
                template.getSubject(),
                template.getBody(),
                template.getCreatedAt(),
                template.getUpdatedAt()
        );
    }
}
