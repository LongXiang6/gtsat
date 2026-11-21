package com.gtsat;

/**
 * Pure mode-selection logic for {@link GtSatMod}, extracted so the degradation
 * matrix can be unit-tested offline without a running game or Mojang classes.
 *
 * <p>Rules (architecture §3 + task-2 "软依赖" requirement):</p>
 * <ul>
 *   <li>{@code mode=scheduler} → run the legacy hash dispatcher (needs preset).</li>
 *   <li>{@code mode=inject} + lithostitched absent → log-and-degrade to scheduler,
 *       NEVER crash.</li>
 *   <li>{@code mode=inject} + lithostitched present but registration threw → degrade.</li>
 *   <li>otherwise → inject.</li>
 * </ul>
 */
public final class ModeDecision {
    public enum Plan {
        SCHEDULER_EXPLICIT,
        INJECT,
        SCHEDULER_FALLBACK_NO_LITHO,
        SCHEDULER_FALLBACK_REGISTRATION_FAILED
    }

    private ModeDecision() {
    }

    public static Plan decide(String mode, boolean lithostitchedLoaded, boolean registrationOk) {
        if ("scheduler".equalsIgnoreCase(mode)) {
            return Plan.SCHEDULER_EXPLICIT;
        }
        if (!lithostitchedLoaded) {
            return Plan.SCHEDULER_FALLBACK_NO_LITHO;
        }
        if (!registrationOk) {
            return Plan.SCHEDULER_FALLBACK_REGISTRATION_FAILED;
        }
        return Plan.INJECT;
    }
}
