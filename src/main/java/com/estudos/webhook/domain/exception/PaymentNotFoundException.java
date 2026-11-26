package com.estudos.webhook.domain.exception;

/**
 * Pagamento não encontrado.
 */
public class PaymentNotFoundException extends ResourceNotFoundException {

    public PaymentNotFoundException(String paymentId) {
        super("Payment '" + paymentId + "' not found");
    }
}
