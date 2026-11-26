package com.estudos.webhook.api.controller;

import com.estudos.webhook.application.dto.OrderView;
import com.estudos.webhook.application.usecase.GetOrderUseCase;
import com.estudos.webhook.domain.exception.OrderNotFoundException;
import com.estudos.webhook.support.TestData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderController.class)
@Import(WebMvcTestSupport.class)
@ActiveProfiles("test")
class OrderControllerTest {

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private GetOrderUseCase getOrder;

    @Test
    void returnsOrderWithPayments() throws Exception {
        when(getOrder.execute(TestData.ORDER_ID)).thenReturn(new OrderView(TestData.ORDER_ID, "PAID", 15990, "BRL",
                List.of(new OrderView.PaymentView(TestData.PAYMENT_ID, "CONFIRMED", 15990, "BRL", "PIX",
                        TestData.NOW))));

        mvc.perform(get("/v1/orders/{id}", TestData.ORDER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.payments[0].status").value("CONFIRMED"))
                .andExpect(jsonPath("$.payments[0].lastEventAt").value("2026-11-26T14:00:00Z"));
    }

    @Test
    void unknownOrderIsNotFound() throws Exception {
        when(getOrder.execute("ord_x")).thenThrow(new OrderNotFoundException("ord_x"));

        mvc.perform(get("/v1/orders/{id}", "ord_x"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Order 'ord_x' not found"));
    }

    @Test
    void unexpectedErrorHidesInternals() throws Exception {
        when(getOrder.execute("ord_boom")).thenThrow(new IllegalStateException("connection refused to 10.0.0.5"));

        mvc.perform(get("/v1/orders/{id}", "ord_boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("An unexpected error occurred."));
    }

    @Test
    void unknownRouteIsNotFound() throws Exception {
        mvc.perform(get("/v1/unknown"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Resource not found"));
    }
}
