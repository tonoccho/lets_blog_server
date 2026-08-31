package com.letsblog.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.StringTemplateResolver;

/**
 * メールテンプレートはDBに文字列として格納されるため、クラスパステンプレートではなく
 * StringTemplateResolverを使う。TEXTモードにすることで、件名・HTML本文どちらも
 * [[${var}]] 記法でそのまま変数展開できる(HTMLタグの厳密なネストを要求されない)。
 */
@Configuration
public class MailConfig {

    @Bean
    public TemplateEngine mailTemplateEngine() {
        StringTemplateResolver templateResolver = new StringTemplateResolver();
        templateResolver.setTemplateMode(TemplateMode.TEXT);
        templateResolver.setCacheable(false);

        TemplateEngine templateEngine = new TemplateEngine();
        templateEngine.setTemplateResolver(templateResolver);
        return templateEngine;
    }
}
