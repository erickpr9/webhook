package com.estudos.webhook.domain.exception;

/**
 * Base das exceções de domínio. Não deve ser lançada diretamente.
 */
public abstract class DomainException extends RuntimeException {

    protected DomainException(String message) {
        super(message);
    }
}
