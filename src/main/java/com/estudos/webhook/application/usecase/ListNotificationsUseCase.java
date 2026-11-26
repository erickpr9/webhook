package com.estudos.webhook.application.usecase;

import com.estudos.webhook.application.dto.NotificationView;
import com.estudos.webhook.application.port.NotificationOutbox;

import java.util.List;
import java.util.Objects;

/**
 * Lista as notificações mais recentes do outbox (entregas ao cliente).
 */
public final class ListNotificationsUseCase {

    public static final int MAX_LIMIT = 500;

    private final NotificationOutbox outbox;

    public ListNotificationsUseCase(NotificationOutbox outbox) {
        this.outbox = Objects.requireNonNull(outbox);
    }

    public List<NotificationView> execute(int limit) {
        return outbox.findRecent(Math.clamp(limit, 1, MAX_LIMIT));
    }
}
