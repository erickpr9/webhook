package com.estudos.webhook.api.controller;

import com.estudos.webhook.api.response.WebhookEventResponse;
import com.estudos.webhook.application.usecase.GetWebhookEventUseCase;
import com.estudos.webhook.application.usecase.ListWebhookEventsUseCase;
import com.estudos.webhook.application.usecase.ReprocessWebhookEventUseCase;
import com.estudos.webhook.domain.model.WebhookEventStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Operação da inbox: consulta de eventos e reprocessamento de eventos DEAD.
 *
 * <p>Sem autenticação: só é registrado com {@code webhook.admin.enabled=true}, que fica ligado
 * apenas no profile {@code local}. Em um ambiente real, proteja com autenticação.
 */
@RestController
@RequestMapping("/admin/webhook-events")
@ConditionalOnProperty(prefix = "webhook.admin", name = "enabled", havingValue = "true")
@Tag(name = "Admin", description = "Operação da inbox de webhooks (somente local)")
public class AdminWebhookEventController {

    private final ListWebhookEventsUseCase listEvents;
    private final GetWebhookEventUseCase getEvent;
    private final ReprocessWebhookEventUseCase reprocessEvent;

    public AdminWebhookEventController(ListWebhookEventsUseCase listEvents, GetWebhookEventUseCase getEvent,
                                       ReprocessWebhookEventUseCase reprocessEvent) {
        this.listEvents = listEvents;
        this.getEvent = getEvent;
        this.reprocessEvent = reprocessEvent;
    }

    @GetMapping
    @Operation(summary = "Lista os eventos mais recentes, opcionalmente por status")
    public List<WebhookEventResponse> list(@RequestParam Optional<WebhookEventStatus> status,
                                           @RequestParam(defaultValue = "50") int limit) {
        return listEvents.execute(status, limit).stream().map(WebhookEventResponse::from).toList();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Consulta um evento pelo identificador interno")
    public WebhookEventResponse get(@PathVariable UUID id) {
        return WebhookEventResponse.from(getEvent.execute(id));
    }

    @PostMapping("/{id}/reprocess")
    @Operation(summary = "Recoloca um evento DEAD na fila de processamento")
    public ResponseEntity<WebhookEventResponse> reprocess(@PathVariable UUID id) {
        return ResponseEntity.accepted().body(WebhookEventResponse.from(reprocessEvent.execute(id)));
    }
}
