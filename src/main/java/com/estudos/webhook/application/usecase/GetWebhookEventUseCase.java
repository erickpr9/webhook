package com.estudos.webhook.application.usecase;

import com.estudos.webhook.application.dto.WebhookEventView;
import com.estudos.webhook.application.port.WebhookEventRepository;
import com.estudos.webhook.domain.exception.WebhookEventNotFoundException;

import java.util.Objects;
import java.util.UUID;

/**
 * Consulta um evento da inbox pelo identificador interno.
 */
public final class GetWebhookEventUseCase {

    private final WebhookEventRepository repository;

    public GetWebhookEventUseCase(WebhookEventRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    public WebhookEventView execute(UUID id) {
        return repository.findById(id)
                .map(WebhookEventView::from)
                .orElseThrow(() -> new WebhookEventNotFoundException(id));
    }
}
