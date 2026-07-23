/**
 * Secure account-recovery state machine.
 *
 * <p>Recovery flows are initiated when a protection decision results in
 * {@code START_RECOVERY}. The module owns explicit state transitions,
 * recovery-specific risk classification (immediate, delayed, manual review),
 * and abuse controls.</p>
 */
@org.springframework.modulith.ApplicationModule
package io.github.viniciusssantos.accountshield.recovery;
