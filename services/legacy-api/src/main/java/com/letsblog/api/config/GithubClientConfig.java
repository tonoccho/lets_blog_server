package com.letsblog.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

/**
 * Spring Bootが自動構成する既定のRestClient.Builderは、内部でHttpURLConnectionベースの
 * ClientHttpRequestFactoryを使用するためPATCHメソッドを送信できない(Invalid HTTP method: PATCH)。
 * GithubClient(issue更新でPATCHを使用)専用に、PATCH対応のJdkClientHttpRequestFactoryを設定したBuilderを用意する。
 */
@Configuration
public class GithubClientConfig {

    @Bean
    public RestClient.Builder githubRestClientBuilder() {
        return RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory(HttpClient.newHttpClient()));
    }
}
