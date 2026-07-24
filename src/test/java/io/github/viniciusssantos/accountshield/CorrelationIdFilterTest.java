package io.github.viniciusssantos.accountshield;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void generatesAndEchoesACorrelationIdWhenNoneSupplied() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> observedDuringRequest = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> observedDuringRequest.set(MDC.get(CorrelationIdFilter.MDC_KEY)));

        assertThat(observedDuringRequest.get()).isNotBlank();
        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo(observedDuringRequest.get());
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void echoesBackASafeClientSuppliedCorrelationId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, "client-abc.123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo("client-abc.123");
    }

    @Test
    void rejectsAnUnsafeClientSuppliedCorrelationId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, "line1\nX-Injected: evil");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).doesNotContain("\n");
    }
}
