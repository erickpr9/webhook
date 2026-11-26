package com.estudos.webhook.application.exception;

import java.util.UUID;

/**
 * O lease de processamento expirou e outro worker assumiu o registro. A transação corrente
 * deve ser desfeita para não aplicar o mesmo efeito duas vezes.
 */
public class LeaseLostException extends RuntimeException {

    public LeaseLostException(UUID id) {
        super("Processing lease lost for '" + id + "'");
    }
}
