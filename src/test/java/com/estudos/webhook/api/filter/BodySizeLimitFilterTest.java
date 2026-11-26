package com.estudos.webhook.api.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.estudos.webhook.support.TestData;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BodySizeLimitFilterTest {

    private static final int LIMIT = 10;

    private final BodySizeLimitFilter filter = new BodySizeLimitFilter(LIMIT,
            new ObjectMapper().registerModule(new JavaTimeModule()), Clock.fixed(TestData.NOW, ZoneOffset.UTC));

    @Test
    void rejectsDeclaredContentLengthAboveLimitWithoutReadingTheBody() throws Exception {
        var request = new MockHttpServletRequest("POST", "/v1/webhooks/payments");
        request.setContent(new byte[LIMIT + 1]);
        var response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {
            throw new AssertionError("chain must not be called");
        };

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("\"status\":413").contains("/v1/webhooks/payments");
    }

    @Test
    void passesBodyWithinLimit() throws Exception {
        var request = new MockHttpServletRequest("POST", "/v1/webhooks/payments");
        request.setContent(new byte[LIMIT]);
        var response = new MockHttpServletResponse();
        int[] bytesRead = {0};

        filter.doFilter(request, response, (req, res) -> bytesRead[0] = req.getInputStream().readAllBytes().length);

        assertThat(bytesRead[0]).isEqualTo(LIMIT);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void stopsReadingUndeclaredBodyWhenItExceedsTheLimit() {
        var request = new MockHttpServletRequest("POST", "/v1/webhooks/payments") {
            @Override
            public long getContentLengthLong() {
                return -1; // transferência em blocos, sem Content-Length
            }
        };
        request.setContent(new byte[LIMIT * 3]);

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> req.getInputStream().readAllBytes()))
                .isInstanceOf(PayloadTooLargeException.class);
    }

    @Test
    void countsSingleByteReads() {
        var request = new MockHttpServletRequest("POST", "/v1/webhooks/payments") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.setContent(new byte[LIMIT + 1]);

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            var in = req.getInputStream();
            while (in.read() != -1) {
                assertThat(in.isReady()).isTrue();
            }
        })).isInstanceOf(PayloadTooLargeException.class);
    }
}
