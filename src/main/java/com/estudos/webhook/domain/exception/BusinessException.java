package com.estudos.webhook.domain.exception;

/**
 * Violação de regra de negócio. Na API vira {@code 422}; no processamento de eventos é
 * tratada como erro permanente (não adianta tentar de novo).
 */
public abstract class BusinessException extends DomainException {

    protected BusinessException(String message) {
        super(message);
    }
}
