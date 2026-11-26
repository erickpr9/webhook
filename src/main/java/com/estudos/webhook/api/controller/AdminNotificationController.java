package com.estudos.webhook.api.controller;

import com.estudos.webhook.application.dto.NotificationView;
import com.estudos.webhook.application.usecase.ListNotificationsUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Acompanhamento das entregas ao cliente (outbox de notificações).
 *
 * <p>Sem autenticação: só é registrado com {@code webhook.admin.enabled=true} (profile {@code local}).
 */
@RestController
@RequestMapping("/admin/notifications")
@ConditionalOnProperty(prefix = "webhook.admin", name = "enabled", havingValue = "true")
@Tag(name = "Admin", description = "Operação da inbox de webhooks (somente local)")
public class AdminNotificationController {

    private final ListNotificationsUseCase listNotifications;

    public AdminNotificationController(ListNotificationsUseCase listNotifications) {
        this.listNotifications = listNotifications;
    }

    @GetMapping
    @Operation(summary = "Notificações mais recentes: status da entrega, tentativas e último erro")
    public List<NotificationView> list(@RequestParam(defaultValue = "20") int limit) {
        return listNotifications.execute(limit);
    }
}
