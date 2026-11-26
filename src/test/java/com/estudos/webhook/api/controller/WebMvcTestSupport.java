package com.estudos.webhook.api.controller;

import com.estudos.webhook.api.config.WebhookApiConfig;
import com.estudos.webhook.api.security.WebhookSecurityProperties;
import com.estudos.webhook.support.TestData;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.time.Clock;
import java.time.ZoneOffset;

/**
 * Configuração comum dos testes {@code @WebMvcTest}: componentes da API e relógio fixo.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(WebhookApiConfig.class)
@EnableConfigurationProperties(WebhookSecurityProperties.class)
class WebMvcTestSupport {

    @Bean
    Clock fixedClock() {
        return Clock.fixed(TestData.NOW, ZoneOffset.UTC);
    }
}
