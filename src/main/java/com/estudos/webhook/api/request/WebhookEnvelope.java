package com.estudos.webhook.api.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Envelope comum a todos os eventos do provedor. Só o envelope é validado no recebimento;
 * o bloco {@code data} é lido no processamento, conforme o tipo do evento.
 *
 * @param id        identificador do evento no provedor
 * @param type      tipo do evento (ex.: {@code payment.confirmed})
 * @param createdAt instante em que o evento ocorreu no provedor
 */
public record WebhookEnvelope(
        @NotBlank @Size(max = 100) String id,
        @NotBlank @Size(max = 100) String type,
        @NotNull @JsonProperty("created_at") Instant createdAt) {
}
