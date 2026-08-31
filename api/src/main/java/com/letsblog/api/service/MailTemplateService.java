package com.letsblog.api.service;

import com.letsblog.api.domain.MailTemplate;
import com.letsblog.api.repository.MailTemplateRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.util.Map;

@Service
@Slf4j
public class MailTemplateService {

    private final MailTemplateRepository mailTemplateRepository;
    private final TemplateEngine mailTemplateEngine;

    public MailTemplateService(
            MailTemplateRepository mailTemplateRepository,
            TemplateEngine mailTemplateEngine) {
        this.mailTemplateRepository = mailTemplateRepository;
        this.mailTemplateEngine = mailTemplateEngine;
    }

    /**
     * テンプレートキーからメールテンプレートを取得し、変数を埋め込む。
     */
    @Transactional(readOnly = true)
    public MailContent renderTemplate(String templateKey, Map<String, Object> variables) {
        MailTemplate template = mailTemplateRepository.findByTemplateKeyAndIsActiveTrue(templateKey)
                .orElseThrow(() -> new MailTemplateNotFoundException("テンプレート '" + templateKey + "' が見つかりません"));

        Context context = new Context();
        context.setVariables(variables);

        String renderedSubject = mailTemplateEngine.process(template.getSubject(), context);
        String renderedBody = mailTemplateEngine.process(template.getBodyHtml(), context);

        return new MailContent(renderedSubject, renderedBody);
    }

    public record MailContent(String subject, String bodyHtml) {}
}
