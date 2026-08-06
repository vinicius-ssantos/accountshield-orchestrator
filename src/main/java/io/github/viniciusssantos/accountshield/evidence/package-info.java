/**
 * Signed, redacted evidence bundles for a single historical decision.
 *
 * <p>Composes the audit trace, a deterministic replay, and the decision's audit-chain proof into
 * one canonical, hash-manifested, digitally signed document. Export is a read-only act over
 * already-recorded evidence: it never mutates protection, recovery, policy, or audit state.</p>
 */
@org.springframework.modulith.ApplicationModule
package io.github.viniciusssantos.accountshield.evidence;
