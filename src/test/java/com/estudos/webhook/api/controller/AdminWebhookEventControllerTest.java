package com.estudos.webhook.api.controller;

import com.estudos.webhook.application.dto.WebhookEventView;
import com.estudos.webhook.application.usecase.GetWebhookEventUseCase;
import com.estudos.webhook.application.usecase.ListWebhookEventsUseCase;
import com.estudos.webhook.application.usecase.ReprocessWebhookEventUseCase;
import com.estudos.webhook.domain.exception.WebhookEventNotFoundException;
import com.estudos.webhook.domain.exception.WebhookEventNotReprocessableException;
import com.estudos.webhook.domain.model.WebhookEventStatus;
import com.estudos.webhook.support.TestData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AdminWebhookEventController.class, properties = "webhook.admin.enabled=true")
@Import(WebMvcTestSupport.class)
@ActiveProfiles("test")
class AdminWebhookEventControllerTest {

    private static final UUID ID = UUID.fromString("7f8c1f6e-0b5a-4f39-9a52-3f1c2d6e8a10");

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private ListWebhookEventsUseCase listEvents;
    @MockitoBean
    private GetWebhookEventUseCase getEvent;
    @MockitoBean
    private ReprocessWebhookEventUseCase reprocessEvent;

    private static WebhookEventView view(String status) {
        return new WebhookEventView(ID, "payment-gateway", "evt_1", "payment.confirmed", status, 6, TestData.NOW,
                TestData.NOW, TestData.NOW, null, null, "PaymentNotFoundException: Payment 'pay_9' not found");
    }

    @Test
    void listsEventsFilteredByStatus() throws Exception {
        when(listEvents.execute(Optional.of(WebhookEventStatus.DEAD), 10)).thenReturn(List.of(view("DEAD")));

        mvc.perform(get("/admin/webhook-events").param("status", "DEAD").param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(ID.toString()))
                .andExpect(jsonPath("$[0].status").value("DEAD"))
                .andExpect(jsonPath("$[0].payload").doesNotExist());
    }

    @Test
    void listsAllEventsWithDefaultLimit() throws Exception {
        when(listEvents.execute(Optional.empty(), 50)).thenReturn(List.of());

        mvc.perform(get("/admin/webhook-events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void rejectsUnknownStatusFilter() throws Exception {
        mvc.perform(get("/admin/webhook-events").param("status", "LOST"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value for parameter 'status'"));
    }

    @Test
    void getsSingleEvent() throws Exception {
        when(getEvent.execute(ID)).thenReturn(view("PROCESSED"));

        mvc.perform(get("/admin/webhook-events/{id}", ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSED"));
    }

    @Test
    void unknownEventIsNotFound() throws Exception {
        when(getEvent.execute(ID)).thenThrow(new WebhookEventNotFoundException(ID));

        mvc.perform(get("/admin/webhook-events/{id}", ID))
                .andExpect(status().isNotFound());
    }

    @Test
    void reprocessesDeadEvent() throws Exception {
        when(reprocessEvent.execute(ID)).thenReturn(view("RETRY_SCHEDULED"));

        mvc.perform(post("/admin/webhook-events/{id}/reprocess", ID))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("RETRY_SCHEDULED"));
    }

    @Test
    void reprocessOfNonDeadEventIsUnprocessable() throws Exception {
        when(reprocessEvent.execute(ID))
                .thenThrow(new WebhookEventNotReprocessableException(ID, WebhookEventStatus.PROCESSED));

        mvc.perform(post("/admin/webhook-events/{id}/reprocess", ID))
                .andExpect(status().isUnprocessableEntity());
    }
}
