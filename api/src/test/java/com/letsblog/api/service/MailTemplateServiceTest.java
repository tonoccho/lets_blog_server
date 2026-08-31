package com.letsblog.api.service;

import com.letsblog.api.domain.MailTemplate;
import com.letsblog.api.repository.MailTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.StringTemplateResolver;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MailTemplateServiceTest {

    @Mock
    private MailTemplateRepository mailTemplateRepository;

    private MailTemplateService service;

    @BeforeEach
    void setUp() {
        StringTemplateResolver resolver = new StringTemplateResolver();
        resolver.setTemplateMode(TemplateMode.TEXT);
        resolver.setCacheable(false);
        TemplateEngine templateEngine = new TemplateEngine();
        templateEngine.setTemplateResolver(resolver);

        service = new MailTemplateService(mailTemplateRepository, templateEngine);
    }

    private MailTemplate buildTemplate() {
        MailTemplate template = new MailTemplate();
        template.setTemplateKey("password-reset");
        template.setSubject("Let's Blog - [[${title}]]");
        template.setBodyHtml("<p>リンク: [[${resetLink}]]</p>");
        template.setIsActive(true);
        return template;
    }

    @Test
    void renderTemplate_変数を埋め込んで件名と本文をレンダリングする() {
        when(mailTemplateRepository.findByTemplateKeyAndIsActiveTrue("password-reset"))
                .thenReturn(Optional.of(buildTemplate()));

        MailTemplateService.MailContent content = service.renderTemplate(
                "password-reset",
                Map.of("title", "パスワード再設定", "resetLink", "https://example.com/reset?token=abc"));

        assertEquals("Let's Blog - パスワード再設定", content.subject());
        assertEquals("<p>リンク: https://example.com/reset?token=abc</p>", content.bodyHtml());
    }

    @Test
    void renderTemplate_存在しないテンプレートキーは例外() {
        when(mailTemplateRepository.findByTemplateKeyAndIsActiveTrue("unknown"))
                .thenReturn(Optional.empty());

        assertThrows(MailTemplateNotFoundException.class,
                () -> service.renderTemplate("unknown", Map.of()));
    }
}
