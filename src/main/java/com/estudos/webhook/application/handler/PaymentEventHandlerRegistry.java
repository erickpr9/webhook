package com.estudos.webhook.application.handler;

import com.estudos.webhook.domain.model.PaymentEventType;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Registro dos handlers por tipo de evento.
 *
 * <p>Falha na inicialização se faltar handler para algum tipo ou houver dois para o mesmo,
 * para que o erro apareça no deploy e não em produção.
 */
public final class PaymentEventHandlerRegistry {

    private final Map<PaymentEventType, PaymentEventHandler> handlers;

    public PaymentEventHandlerRegistry(List<? extends PaymentEventHandler> handlerList) {
        Map<PaymentEventType, PaymentEventHandler> byType = new EnumMap<>(PaymentEventType.class);
        for (PaymentEventHandler handler : handlerList) {
            PaymentEventHandler previous = byType.putIfAbsent(handler.supportedType(), handler);
            if (previous != null) {
                throw new IllegalStateException("Duplicate handler for " + handler.supportedType());
            }
        }
        List<PaymentEventType> missing = Arrays.stream(PaymentEventType.values())
                .filter(type -> !byType.containsKey(type))
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Missing handlers for " + missing);
        }
        this.handlers = Collections.unmodifiableMap(byType);
    }

    public PaymentEventHandler handlerFor(PaymentEventType type) {
        return handlers.get(type);
    }
}
