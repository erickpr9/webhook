package com.estudos.webhook.application.port;

import com.estudos.webhook.application.exception.ConcurrentUpdateException;
import com.estudos.webhook.domain.model.Payment;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Porta de persistência de pagamentos.
 */
public interface PaymentRepository {

    Optional<Payment> findById(String id);

    List<Payment> findByOrderId(String orderId);

    void insert(Payment payment, Instant now);

    /**
     * Atualiza o pagamento usando a versão lida como controle otimista.
     *
     * @throws ConcurrentUpdateException se outro processo alterou o pagamento nesse meio-tempo
     */
    void update(Payment payment, Instant now);
}
