package com.estudos.webhook.dev;

import com.estudos.webhook.shared.security.HmacSigner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestClient;

import java.time.Clock;

/**
 * Registra a simulação do provedor apenas quando {@code webhook.dev.simulation-enabled=true}.
 * Fica fora das camadas da aplicação por ser ferramenta de desenvolvimento.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "webhook.dev", name = "simulation-enabled", havingValue = "true")
public class DevSimulationConfig {

    @Bean
    PaymentProviderSimulator paymentProviderSimulator(HmacSigner signer, ObjectMapper objectMapper,
                                                      Environment environment, Clock clock,
                                                      RestClient.Builder restClientBuilder) {
        return new PaymentProviderSimulator(signer, objectMapper, environment, clock, restClientBuilder);
    }
}
