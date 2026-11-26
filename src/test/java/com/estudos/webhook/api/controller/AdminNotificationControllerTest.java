package com.estudos.webhook.api.controller;

import com.estudos.webhook.application.dto.NotificationView;
import com.estudos.webhook.application.port.NotificationOutbox;
import com.estudos.webhook.application.usecase.ListNotificationsUseCase;
import com.estudos.webhook.support.TestData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AdminNotificationController.class, properties = "webhook.admin.enabled=true")
@Import(WebMvcTestSupport.class)
@ActiveProfiles("test")
class AdminNotificationControllerTest {

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private ListNotificationsUseCase listNotifications;

    @Test
    void listsNotificationsWithDeliveryStatus() throws Exception {
        when(listNotifications.execute(20)).thenReturn(List.of(new NotificationView(UUID.randomUUID(),
                "PAYMENT_CONFIRMED", TestData.ORDER_ID, TestData.PAYMENT_ID, "RETRY_SCHEDULED", 2, TestData.NOW,
                TestData.NOW.plusSeconds(30), null, "NotificationDeliveryException: Client endpoint answered 503")));

        mvc.perform(get("/admin/notifications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("RETRY_SCHEDULED"))
                .andExpect(jsonPath("$[0].attempts").value(2))
                .andExpect(jsonPath("$[0].orderId").value(TestData.ORDER_ID));
    }

    @Test
    void useCaseClampsTheLimit() {
        NotificationOutbox outbox = mock(NotificationOutbox.class);
        when(outbox.findRecent(ListNotificationsUseCase.MAX_LIMIT)).thenReturn(List.of());

        assertThat(new ListNotificationsUseCase(outbox).execute(100_000)).isEmpty();
        verify(outbox).findRecent(ListNotificationsUseCase.MAX_LIMIT);
    }
}
