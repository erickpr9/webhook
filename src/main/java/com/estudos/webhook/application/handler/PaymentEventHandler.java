package com.estudos.webhook.application.handler;

import com.estudos.webhook.application.dto.HandlingOutcome;
import com.estudos.webhook.application.dto.PaymentEventData;
import com.estudos.webhook.domain.model.PaymentEventType;

import java.time.Instant;

/**
 * Trata um tipo específico de evento de pagamento (Strategy).
 *
 * <p>Roda dentro da transação do processamento do evento: qualquer exceção desfaz todas as
 * alterações e aciona a política de retentativa.
 */
public interface PaymentEventHandler {

    /** Tipo de evento atendido por este handler. */
    PaymentEventType supportedType();

    /**
     * @param data       dados de pagamento do evento
     * @param occurredAt instante do evento no provedor
     */
    HandlingOutcome handle(PaymentEventData data, Instant occurredAt);
}
