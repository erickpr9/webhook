package com.estudos.webhook.domain.exception;

/**
 * Os dados do evento (pedido ou valor) não batem com o pagamento registrado.
 * Indica inconsistência com o provedor e exige análise manual.
 */
public class PaymentDataMismatchException extends BusinessException {

    public PaymentDataMismatchException(String paymentId) {
        super("Event data does not match payment '" + paymentId + "' (order or amount differs)");
    }
}
