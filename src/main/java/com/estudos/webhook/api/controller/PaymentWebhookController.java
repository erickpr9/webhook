package com.estudos.webhook.api.controller;

import com.estudos.webhook.api.request.InvalidWebhookRequestException;
import com.estudos.webhook.api.request.WebhookEnvelope;
import com.estudos.webhook.api.request.WebhookEnvelopeReader;
import com.estudos.webhook.api.response.WebhookReceivedResponse;
import com.estudos.webhook.api.security.WebhookSecurityProperties;
import com.estudos.webhook.api.security.WebhookSignatureVerifier;
import com.estudos.webhook.application.dto.RegisterWebhookEventCommand;
import com.estudos.webhook.application.dto.RegistrationResult;
import com.estudos.webhook.application.usecase.RegisterWebhookEventUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * Recebe os webhooks do provedor de pagamentos.
 *
 * <p>Controller fino: autentica, valida o envelope e grava na inbox. O processamento é
 * assíncrono. Ordem das validações (da mais barata para a mais cara): tamanho do corpo
 * (filtro), headers, janela de tempo, assinatura e, por fim, o JSON.
 */
@RestController
@RequestMapping("/v1/webhooks/payments")
@Tag(name = "Payment webhooks", description = "Recebimento de eventos do provedor de pagamentos")
public class PaymentWebhookController {

    public static final String EVENT_ID_HEADER = "X-Event-Id";
    public static final String TIMESTAMP_HEADER = "X-Timestamp";
    public static final String SIGNATURE_HEADER = "X-Signature";

    private final WebhookSignatureVerifier signatureVerifier;
    private final WebhookEnvelopeReader envelopeReader;
    private final RegisterWebhookEventUseCase registerWebhookEvent;
    private final String provider;

    public PaymentWebhookController(WebhookSignatureVerifier signatureVerifier, WebhookEnvelopeReader envelopeReader,
                                    RegisterWebhookEventUseCase registerWebhookEvent,
                                    WebhookSecurityProperties properties) {
        this.signatureVerifier = signatureVerifier;
        this.envelopeReader = envelopeReader;
        this.registerWebhookEvent = registerWebhookEvent;
        this.provider = properties.provider();
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Recebe um evento de pagamento assinado com HMAC-SHA256")
    @ApiResponse(responseCode = "202", description = "Evento novo, aceito para processamento")
    @ApiResponse(responseCode = "200", description = "Evento já recebido antes (reentrega)")
    @ApiResponse(responseCode = "400", description = "Headers ou payload inválidos")
    @ApiResponse(responseCode = "401", description = "Assinatura inválida ou fora da janela de tempo")
    @ApiResponse(responseCode = "413", description = "Corpo acima do limite")
    public ResponseEntity<WebhookReceivedResponse> receive(
            @RequestHeader(EVENT_ID_HEADER) String eventId,
            @RequestHeader(TIMESTAMP_HEADER) String timestamp,
            @RequestHeader(SIGNATURE_HEADER) String signature,
            @RequestBody byte[] body) {

        signatureVerifier.verify(timestamp, signature, body);

        WebhookEnvelope envelope = envelopeReader.read(body);
        if (!envelope.id().equals(eventId)) {
            throw new InvalidWebhookRequestException(EVENT_ID_HEADER + " does not match the payload id");
        }

        RegistrationResult result = registerWebhookEvent.execute(new RegisterWebhookEventCommand(
                provider, eventId, envelope.type(), envelope.createdAt(), new String(body, StandardCharsets.UTF_8)));

        return result.duplicate()
                ? ResponseEntity.ok(WebhookReceivedResponse.duplicate(eventId))
                : ResponseEntity.accepted().body(WebhookReceivedResponse.received(eventId));
    }
}
