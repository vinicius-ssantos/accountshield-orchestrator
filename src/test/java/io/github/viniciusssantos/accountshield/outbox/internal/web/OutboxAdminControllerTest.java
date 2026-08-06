package io.github.viniciusssantos.accountshield.outbox.internal.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.viniciusssantos.accountshield.outbox.OutboxAdminService;
import io.github.viniciusssantos.accountshield.outbox.OutboxEventNotDeadLetteredException;
import io.github.viniciusssantos.accountshield.outbox.OutboxEventNotFoundException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OutboxAdminControllerTest {

    private final OutboxAdminService outboxAdminService = mock(OutboxAdminService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new OutboxAdminController(outboxAdminService))
                .setControllerAdvice(new OutboxProblemHandler())
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .build();
    }

    /**
     * Issue #153 / F-25: the controller's contract promises a default page size of 50 without a
     * {@code size} query parameter; nothing previously enforced this at the endpoint (only the
     * service-layer {@code MAX_PAGE_SIZE} cap was ever exercised end to end).
     */
    @Test
    void listDefaultsToAPageSizeOf50WhenNoSizeIsRequested() throws Exception {
        when(outboxAdminService.list(isNull(), any(Pageable.class))).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/outbox")
                        .principal(new TestingAuthenticationToken("operator-1", null)))
                .andExpect(status().isOk());

        verify(outboxAdminService).list(isNull(), eq(Pageable.ofSize(50)));
    }

    @Test
    void requeueReturns204OnSuccess() throws Exception {
        UUID eventId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/outbox/" + eventId + "/requeue")
                        .principal(new TestingAuthenticationToken("operator-1", null)))
                .andExpect(status().isNoContent());

        verify(outboxAdminService).requeue(eventId, "operator-1");
    }

    @Test
    void requeueReturns404WhenEventNotFound() throws Exception {
        UUID eventId = UUID.randomUUID();
        doThrow(new OutboxEventNotFoundException(eventId))
                .when(outboxAdminService).requeue(eventId, "operator-1");

        mockMvc.perform(post("/api/v1/outbox/" + eventId + "/requeue")
                        .principal(new TestingAuthenticationToken("operator-1", null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("OUTBOX_EVENT_NOT_FOUND"));
    }

    @Test
    void requeueReturns409WhenNotDeadLettered() throws Exception {
        UUID eventId = UUID.randomUUID();
        doThrow(new OutboxEventNotDeadLetteredException(eventId, "PENDING"))
                .when(outboxAdminService).requeue(eventId, "operator-1");

        mockMvc.perform(post("/api/v1/outbox/" + eventId + "/requeue")
                        .principal(new TestingAuthenticationToken("operator-1", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OUTBOX_EVENT_NOT_DEAD_LETTERED"));
    }
}
