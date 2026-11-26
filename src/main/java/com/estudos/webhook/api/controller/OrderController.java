package com.estudos.webhook.api.controller;

import com.estudos.webhook.api.response.OrderResponse;
import com.estudos.webhook.application.usecase.GetOrderUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Consulta de pedidos: permite acompanhar o efeito dos webhooks no domínio.
 */
@RestController
@RequestMapping("/v1/orders")
@Tag(name = "Orders", description = "Consulta de pedidos e pagamentos")
public class OrderController {

    private final GetOrderUseCase getOrder;

    public OrderController(GetOrderUseCase getOrder) {
        this.getOrder = getOrder;
    }

    @GetMapping("/{orderId}")
    @Operation(summary = "Consulta um pedido e o estado dos seus pagamentos")
    public OrderResponse get(@PathVariable String orderId) {
        return OrderResponse.from(getOrder.execute(orderId));
    }
}
