package io.github.viniciusssantos.accountshield;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

class ProblemDetailCorrelationAdviceTest {

    private final ProblemDetailCorrelationAdvice advice = new ProblemDetailCorrelationAdvice();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void stampsTheCurrentCorrelationIdOntoAProblemDetailBody() {
        MDC.put(CorrelationIdFilter.MDC_KEY, "corr-123");
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);

        Object result = advice.beforeBodyWrite(problem, null, null, null, null, null);

        assertThat(result).isSameAs(problem);
        assertThat(problem.getProperties()).containsEntry("correlationId", "corr-123");
    }

    @Test
    void leavesNonProblemDetailBodiesUntouched() {
        MDC.put(CorrelationIdFilter.MDC_KEY, "corr-123");
        String body = "not a problem";

        Object result = advice.beforeBodyWrite(body, null, null, null, null, null);

        assertThat(result).isSameAs(body);
    }

    @Test
    void doesNotAddAPropertyWhenNoCorrelationIdIsAvailable() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);

        advice.beforeBodyWrite(problem, null, null, null, null, null);

        assertThat(problem.getProperties()).isNull();
    }
}
