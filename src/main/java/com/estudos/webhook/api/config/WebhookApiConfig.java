package com.estudos.webhook.api.config;

import com.estudos.webhook.api.filter.BodySizeLimitFilter;
import com.estudos.webhook.api.request.WebhookEnvelopeReader;
import com.estudos.webhook.shared.security.HmacSigner;
import com.estudos.webhook.api.security.WebhookSecurityProperties;
import com.estudos.webhook.api.security.WebhookSignatureVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.time.Clock;

/**
 * Componentes da entrada HTTP do webhook.
 */
@Configuration(proxyBeanMethods = false)
public class WebhookApiConfig {

    @Bean
    HmacSigner webhookHmacSigner(WebhookSecurityProperties properties) {
        return new HmacSigner(properties.secret());
    }

    @Bean
    WebhookSignatureVerifier webhookSignatureVerifier(HmacSigner signer, WebhookSecurityProperties properties,
                                                      Clock clock) {
        return new WebhookSignatureVerifier(signer, properties.tolerance(), clock);
    }

    @Bean
    WebhookEnvelopeReader webhookEnvelopeReader(ObjectMapper objectMapper, Validator validator) {
        return new WebhookEnvelopeReader(objectMapper, validator);
    }

    /** Aplica o limite de tamanho somente às rotas de webhook. */
    @Bean
    FilterRegistrationBean<BodySizeLimitFilter> webhookBodySizeLimitFilter(WebhookSecurityProperties properties,
                                                                           ObjectMapper objectMapper, Clock clock) {
        var registration = new FilterRegistrationBean<>(
                new BodySizeLimitFilter(properties.maxBodySize().toBytes(), objectMapper, clock));
        registration.addUrlPatterns("/v1/webhooks/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}
