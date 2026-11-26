package com.estudos.webhook.application.dto;

/**
 * Resultado do consumo de uma mensagem da esteira. Diz ao adaptador de mensageria o que fazer
 * em seguida, sem que a aplicação conheça o broker.
 */
public enum ConsumeOutcome {

    /** Tratada com sucesso: a mensagem pode ser confirmada. */
    COMPLETED,

    /** Já tinha sido tratada (reentrega): confirma sem efeito. */
    ALREADY_HANDLED,

    /** Falha transitória: a mensagem deve ser tentada de novo mais tarde. */
    RETRY,

    /** Falha permanente ou tentativas esgotadas: a mensagem vai para a dead-letter. */
    DEAD
}
