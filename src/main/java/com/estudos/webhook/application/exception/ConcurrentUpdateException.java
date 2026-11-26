package com.estudos.webhook.application.exception;

/**
 * Outro processo alterou o registro entre a leitura e a escrita (falha do lock otimista).
 * É transitória: uma nova tentativa relê o estado atualizado.
 */
public class ConcurrentUpdateException extends RuntimeException {

    public ConcurrentUpdateException(String entity, String id) {
        super(entity + " '" + id + "' was modified concurrently");
    }
}
