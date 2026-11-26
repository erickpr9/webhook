package com.estudos.webhook.support;

import com.estudos.webhook.application.port.TransactionRunner;

import java.util.function.Supplier;

/**
 * {@link TransactionRunner} de teste: executa o bloco diretamente, sem transação.
 */
public final class DirectTransactionRunner implements TransactionRunner {

    private int executions;

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        executions++;
        return work.get();
    }

    public int executions() {
        return executions;
    }
}
