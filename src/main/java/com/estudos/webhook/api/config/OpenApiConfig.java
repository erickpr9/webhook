package com.estudos.webhook.api.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Metadados da documentação OpenAPI (Swagger UI em {@code /swagger-ui.html} no profile local).
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    @Bean
    OpenAPI webhookOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Payment Webhook Study")
                .version("v1")
                .description("Recebimento, processamento e consumo de webhooks de confirmação de pagamento."));
    }
}
