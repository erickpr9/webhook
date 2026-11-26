package com.estudos.webhook.application.exception;

import com.estudos.webhook.domain.exception.BusinessException;

/**
 * Decide se uma falha de processamento é transitória (vale tentar de novo) ou permanente.
 *
 * <p>Permanentes: payload malformado, violações de regra de negócio e recusa definitiva do
 * destinatário de uma notificação. Todo o resto (banco indisponível, timeout, destinatário
 * fora do ar, recurso ainda não criado, conflito de concorrência) é tratado como transitório.
 */
public final class FailureClassifier {

    private static final int MAX_ERROR_LENGTH = 2000;

    private FailureClassifier() {
    }

    public static boolean isRetryable(Throwable failure) {
        return !(failure instanceof MalformedPayloadException
                || failure instanceof BusinessException
                || failure instanceof PermanentDeliveryException);
    }

    /** Descrição curta da falha para persistência, limitada a 2000 caracteres. */
    public static String describe(Throwable failure) {
        String description = failure.getClass().getSimpleName() + ": " + failure.getMessage();
        return description.length() <= MAX_ERROR_LENGTH ? description : description.substring(0, MAX_ERROR_LENGTH);
    }
}
