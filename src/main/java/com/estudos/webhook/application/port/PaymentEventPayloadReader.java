package com.estudos.webhook.application.port;

import com.estudos.webhook.application.dto.PaymentEventData;
import com.estudos.webhook.application.exception.MalformedPayloadException;

/**
 * Porta de leitura dos dados de pagamento contidos no payload JSON do evento.
 */
public interface PaymentEventPayloadReader {

    /**
     * @throws MalformedPayloadException se o payload não tiver os campos obrigatórios
     */
    PaymentEventData read(String payload);
}
