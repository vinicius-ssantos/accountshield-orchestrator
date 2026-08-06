package io.github.viniciusssantos.accountshield.scenarios;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * One adversarial scenario's expected-vs-actual comparison, rendered into the shared lab report
 * (issue #54: "reports show the complete decision timeline", "failure output clearly identifies
 * policy divergence"). A scenario's JUnit assertions are still the actual pass/fail signal; this
 * report is the human-readable artifact that survives alongside them.
 */
public record ScenarioReport(
        String scenarioName,
        String syntheticInputsSummary,
        int expectedScore,
        int actualScore,
        List<String> expectedReasonCodes,
        List<String> actualReasonCodes,
        String expectedOutcome,
        String actualOutcome,
        String policyKey,
        String policyVersion,
        String additionalNotes) {

    public boolean matchesExpectations() {
        Set<String> expected = new HashSet<>(expectedReasonCodes);
        Set<String> actual = new HashSet<>(actualReasonCodes);
        return expectedScore == actualScore && expectedOutcome.equals(actualOutcome) && expected.equals(actual);
    }

    public String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("## ").append(scenarioName)
                .append(matchesExpectations() ? " ✅" : " ❌ POLICY DIVERGENCE")
                .append("\n\n");
        sb.append("1. **Synthetic input:** ").append(syntheticInputsSummary).append("\n");
        sb.append("2. **Generated signals -> risk assessment:** score=").append(actualScore)
                .append(" (expected ").append(expectedScore).append("), reasons=")
                .append(actualReasonCodes).append(" (expected ").append(expectedReasonCodes).append(")\n");
        sb.append("3. **Selected policy:** ").append(policyKey).append(':').append(policyVersion).append('\n');
        sb.append("4. **Decision outcome:** ").append(actualOutcome)
                .append(" (expected ").append(expectedOutcome).append(")\n");
        if (additionalNotes != null && !additionalNotes.isBlank()) {
            sb.append("5. **Downstream actions/events:** ").append(additionalNotes).append('\n');
        }
        sb.append('\n');
        return sb.toString();
    }
}
