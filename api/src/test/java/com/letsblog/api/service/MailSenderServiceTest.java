package com.letsblog.api.service;

import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MailSenderServiceTest {

    @Mock
    private JavaMailSender mailSender;

    @Mock
    private MailTemplateService mailTemplateService;

    private MailSenderService service;

    @BeforeEach
    void setUp() {
        service = new MailSenderService(mailSender, mailTemplateService);
        ReflectionTestUtils.setField(service, "fromEmail", "noreply@letsblog.example.com");
    }

    @Test
    void sendMail_テンプレートをレンダリングしてHTMLメールを送信する() {
        MimeMessage mimeMessage = mock(MimeMessage.class);
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
        when(mailTemplateService.renderTemplate("password-reset", Map.of("resetLink", "https://example.com")))
                .thenReturn(new MailTemplateService.MailContent("件名", "<p>本文</p>"));

        service.sendMail("user@example.com", "password-reset", Map.of("resetLink", "https://example.com"));

        verify(mailSender, times(1)).send(mimeMessage);
    }

    @Test
    void sendHtmlMail_送信失敗時はEmailSendExceptionを投げる() {
        MimeMessage mimeMessage = mock(MimeMessage.class);
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
        doThrow(new org.springframework.mail.MailSendException("smtp down"))
                .when(mailSender).send(any(MimeMessage.class));

        assertThrows(EmailSendException.class,
                () -> service.sendHtmlMail("user@example.com", "件名", "<p>本文</p>"));
    }

    @Test
    void sendMail_テンプレートが見つからない場合は例外がそのまま伝播しメールは送信されない() {
        when(mailTemplateService.renderTemplate("unknown", Map.of()))
                .thenThrow(new MailTemplateNotFoundException("テンプレート 'unknown' が見つかりません"));

        assertThrows(MailTemplateNotFoundException.class,
                () -> service.sendMail("user@example.com", "unknown", Map.of()));

        verify(mailSender, never()).send(any(MimeMessage.class));
    }
}
