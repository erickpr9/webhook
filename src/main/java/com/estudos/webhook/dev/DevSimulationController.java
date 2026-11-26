package com.estudos.webhook.dev;

import com.estudos.webhook.application.dto.CreateOrderCommand;
import com.estudos.webhook.application.dto.CreatedOrder;
import com.estudos.webhook.application.dto.OrderView;
import com.estudos.webhook.application.usecase.CreateOrderUseCase;
import com.estudos.webhook.application.usecase.GetOrderUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Endpoints de desenvolvimento para exercitar a esteira completa sem um provedor real.
 * Só registrado com {@code webhook.dev.simulation-enabled=true} (profile {@code local}).
 */
@RestController
@RequestMapping("/dev")
@ConditionalOnProperty(prefix = "webhook.dev", name = "simulation-enabled", havingValue = "true")
@Tag(name = "Dev simulation", description = "Simulação do provedor de pagamentos (somente local)")
public class DevSimulationController {

    private final CreateOrderUseCase createOrder;
    private final GetOrderUseCase getOrder;
    private final PaymentProviderSimulator simulator;
    private final Clock clock;

    public DevSimulationController(CreateOrderUseCase createOrder, GetOrderUseCase getOrder,
                                   PaymentProviderSimulator simulator, Clock clock) {
        this.createOrder = createOrder;
        this.getOrder = getOrder;
        this.simulator = simulator;
        this.clock = clock;
    }

    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Cria um pedido com pagamento pendente")
    public CreatedOrder createOrder(@Valid @RequestBody CreateOrderRequest request) {
        return createOrder.execute(new CreateOrderCommand(request.amountInCents(), request.currency(), request.method()));
    }

    @PostMapping("/simulations/payment-events")
    @Operation(summary = "Envia um webhook assinado para o pagamento do pedido, como o provedor faria")
    public PaymentProviderSimulator.SimulationResult simulate(@Valid @RequestBody SimulateEventRequest request) {
        OrderView order = getOrder.execute(request.orderId());
        OrderView.PaymentView payment = order.payments().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Order " + order.orderId() + " has no payment"));

        String eventId = request.eventId() != null ? request.eventId() : "evt_" + UUID.randomUUID();
        Instant occurredAt = request.occurredAt() != null ? request.occurredAt() : clock.instant();
        long amount = request.amountInCents() != null ? request.amountInCents() : payment.amountInCents();

        return simulator.send(new PaymentProviderSimulator.SimulatedEvent(eventId, request.type(), occurredAt,
                payment.paymentId(), order.orderId(), amount, payment.currency(), payment.method()));
    }

    /**
     * @param amountInCents valor em centavos
     * @param currency      moeda ISO 4217
     * @param method        PIX, CREDIT_CARD, DEBIT_CARD ou BOLETO
     */
    public record CreateOrderRequest(
            @Positive long amountInCents,
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
            @NotBlank @Pattern(regexp = "PIX|CREDIT_CARD|DEBIT_CARD|BOLETO") String method) {
    }

    /**
     * @param orderId       pedido cujo pagamento recebe o evento
     * @param type          tipo do evento (ex.: payment.confirmed)
     * @param eventId       opcional; repita um id para simular reentrega
     * @param occurredAt    opcional; use um instante antigo para simular evento fora de ordem
     * @param amountInCents opcional; use um valor diferente para simular divergência
     */
    public record SimulateEventRequest(
            @NotBlank String orderId,
            @NotBlank String type,
            String eventId,
            Instant occurredAt,
            @Positive Long amountInCents) {
    }
}
