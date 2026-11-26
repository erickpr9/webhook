package com.estudos.webhook.application.usecase;

import com.estudos.webhook.application.dto.WebhookEventView;
import com.estudos.webhook.application.port.WebhookEventRepository;
import com.estudos.webhook.domain.model.WebhookEventStatus;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Lista os eventos mais recentes da inbox, opcionalmente por status.
 */
public final class ListWebhookEventsUseCase {

    /** Limite máximo de itens por consulta, para proteger o banco. */
    public static final int MAX_LIMIT = 500;

    private final WebhookEventRepository repository;

    public ListWebhookEventsUseCase(WebhookEventRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    /**
     * @param status filtro opcional
     * @param limit  quantidade desejada; ajustada para o intervalo [1, {@value #MAX_LIMIT}]
     */
    public List<WebhookEventView> execute(Optional<WebhookEventStatus> status, int limit) {
        return repository.findRecent(status, Math.clamp(limit, 1, MAX_LIMIT)).stream()
                .map(WebhookEventView::from)
                .toList();
    }
}
