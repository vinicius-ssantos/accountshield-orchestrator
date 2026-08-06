package io.github.viniciusssantos.accountshield.policy;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Privacy-minimized read port for the security-operations policy directory.
 *
 * <p>{@link #search()} backs an authorized listing endpoint. {@link #investigate(String)} has no
 * HTTP endpoint of its own in this module; it is consumed internally by the {@code investigation}
 * module, which composes it with rollout and impact-analysis data owned by other modules.</p>
 */
public interface PolicyDirectoryQuery {

    int MAX_POLICY_KEYS = 200;

    List<PolicySummary> search();

    Optional<PolicyLifecycleDetail> investigate(String policyKey);

    record PolicySummary(
            String policyKey,
            int totalVersions,
            String activeVersion,
            Instant activeVersionActivatedAt,
            boolean hasActiveRollout) {

        public PolicySummary {
            policyKey = requireText(policyKey, "policyKey");
            if (totalVersions < 0) {
                throw new IllegalArgumentException("totalVersions must not be negative");
            }
        }
    }

    record RoutingScopeEntry(String clientId, String eventType) {
        public RoutingScopeEntry {
            clientId = requireText(clientId, "clientId");
            eventType = requireText(eventType, "eventType");
        }
    }

    record PolicyLifecycleDetail(
            String policyKey,
            List<PolicyVersionSummary> versions,
            List<RoutingScopeEntry> routingScope) {

        public PolicyLifecycleDetail {
            policyKey = requireText(policyKey, "policyKey");
            versions = List.copyOf(Objects.requireNonNull(versions, "versions must not be null"));
            routingScope = List.copyOf(Objects.requireNonNull(routingScope, "routingScope must not be null"));
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
