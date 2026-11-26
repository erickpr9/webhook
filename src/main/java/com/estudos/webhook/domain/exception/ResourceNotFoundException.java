package com.estudos.webhook.domain.exception;

/**
 * Recurso inexistente. Na API vira {@code 404}; no processamento de eventos é tratado como
 * erro transitório, pois o recurso pode ser criado depois (eventos fora de ordem).
 */
public abstract class ResourceNotFoundException extends DomainException {

    protected ResourceNotFoundException(String message) {
        super(message);
    }
}
