/**
 * Deterministic replay, shadow-policy evaluation, and policy comparison.
 *
 * <p>Replays reconstruct historical decisions from the audit trace and
 * re-evaluate them against the original or candidate policy versions.
 * No replay or shadow evaluation ever triggers external side effects.</p>
 */
package io.github.viniciusssantos.accountshield.simulation;
