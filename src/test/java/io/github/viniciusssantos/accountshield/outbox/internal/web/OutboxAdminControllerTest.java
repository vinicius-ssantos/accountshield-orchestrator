package io.github.viniciusssantos.accountshield.outbox.internal.web;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.viniciusssantos.accountshield.outbox.OutboxAdminService;
import io.github.viniciusssantos.accountshield.outbox.OutboxEventNotDeadLetteredException;
import io.github.viniciusssantos.accountshield.outbox.OutboxEventNotFoundException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
                .build();
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
