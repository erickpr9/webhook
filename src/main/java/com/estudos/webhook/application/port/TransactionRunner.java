package com.estudos.webhook.application.port;

import java.util.function.Supplier;

/**
 * Executa um bloco de código dentro de uma transação. Mantém a camada de aplicação
 * independente do mecanismo transacional do framework.
 */
public interface TransactionRunner {

    /**
     * Executa {@code work} em uma transação nova ou na corrente. Exceções não verificadas
     * desfazem a transação e são propagadas.
     */
    <T> T inTransaction(Supplier<T> work);

    default void inTransaction(Runnable work) {
        inTransaction(() -> {
            work.run();
            return null;
        });
    }
}
