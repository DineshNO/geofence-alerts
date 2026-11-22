package io.geoalert.alerts.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.geoalert.alerts.domain.AlertRules.Decision;
import io.geoalert.alerts.domain.AlertRules.Refusal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** The lifecycle rules as plain logic: every allowed move, every refusal, every repeat. */
class AlertRulesTest {

    private static final Actor ALEX = new Actor("alex", false);
    private static final Actor BEA = new Actor("bea", false);
    private static final Actor SUPERVISOR = new Actor("sam", true);

    private static Alert alert(AlertStatus status, String claimedBy, String resolvedBy) {
        Instant t = Instant.parse("2026-11-02T06:00:00Z");
        return new Alert(1, "AST-1", "Z-01", status, t, "r-1", t, claimedBy, null, resolvedBy, null, null);
    }

    private static void assertRefused(Decision decision, Refusal expected) {
        assertThat(decision).isInstanceOf(Decision.Refuse.class);
        assertThat(((Decision.Refuse) decision).reason()).isEqualTo(expected);
    }

    // ---- claim

    @Test
    void anyOperatorMayClaimAnOpenAlert() {
        assertThat(AlertRules.claim(alert(AlertStatus.OPEN, null, null), ALEX)).isInstanceOf(Decision.Apply.class);
        assertThat(AlertRules.claim(alert(AlertStatus.OPEN, null, null), SUPERVISOR))
                .isInstanceOf(Decision.Apply.class);
    }

    @Test
    void claimingYourOwnClaimedAlertChangesNothing() {
        assertThat(AlertRules.claim(alert(AlertStatus.CLAIMED, "alex", null), ALEX))
                .isInstanceOf(Decision.NoChange.class);
    }

    @Test
    void claimingAnAlertSomebodyElseOwnsIsRefused() {
        assertRefused(AlertRules.claim(alert(AlertStatus.CLAIMED, "alex", null), BEA), Refusal.ALREADY_CLAIMED);
        // even a supervisor cannot take it by claiming: they release it first
        assertRefused(
                AlertRules.claim(alert(AlertStatus.CLAIMED, "alex", null), SUPERVISOR),
                Refusal.ALREADY_CLAIMED);
    }

    @Test
    void claimingAResolvedAlertIsAnInvalidState() {
        assertRefused(AlertRules.claim(alert(AlertStatus.RESOLVED, "alex", "alex"), ALEX), Refusal.INVALID_STATE);
    }

    // ---- release

    @Test
    void theOwnerAndASupervisorMayRelease() {
        assertThat(AlertRules.release(alert(AlertStatus.CLAIMED, "alex", null), ALEX))
                .isInstanceOf(Decision.Apply.class);
        assertThat(AlertRules.release(alert(AlertStatus.CLAIMED, "alex", null), SUPERVISOR))
                .isInstanceOf(Decision.Apply.class);
    }

    @Test
    void anotherOperatorMayNotRelease() {
        assertRefused(AlertRules.release(alert(AlertStatus.CLAIMED, "alex", null), BEA), Refusal.NOT_OWNER);
    }

    @Test
    void releasingAnAlertThatIsNotClaimedIsAnInvalidState() {
        assertRefused(AlertRules.release(alert(AlertStatus.OPEN, null, null), ALEX), Refusal.INVALID_STATE);
        assertRefused(AlertRules.release(alert(AlertStatus.RESOLVED, "alex", "alex"), ALEX), Refusal.INVALID_STATE);
        // the state is judged before the owner, so an operator releasing an open alert gets 409
        assertRefused(AlertRules.release(alert(AlertStatus.OPEN, null, null), BEA), Refusal.INVALID_STATE);
    }

    // ---- resolve

    @Test
    void theOwnerAndASupervisorMayResolve() {
        assertThat(AlertRules.resolve(alert(AlertStatus.CLAIMED, "alex", null), ALEX))
                .isInstanceOf(Decision.Apply.class);
        assertThat(AlertRules.resolve(alert(AlertStatus.CLAIMED, "alex", null), SUPERVISOR))
                .isInstanceOf(Decision.Apply.class);
    }

    @Test
    void anotherOperatorMayNotResolve() {
        assertRefused(AlertRules.resolve(alert(AlertStatus.CLAIMED, "alex", null), BEA), Refusal.NOT_OWNER);
    }

    @Test
    void resolvingAnOpenAlertIsAnInvalidState() {
        assertRefused(AlertRules.resolve(alert(AlertStatus.OPEN, null, null), ALEX), Refusal.INVALID_STATE);
    }

    @Test
    void resolvingAgainBySameUserChangesNothingButByAnotherUserIsRefused() {
        Alert resolved = alert(AlertStatus.RESOLVED, "alex", "alex");
        assertThat(AlertRules.resolve(resolved, ALEX)).isInstanceOf(Decision.NoChange.class);
        assertRefused(AlertRules.resolve(resolved, BEA), Refusal.INVALID_STATE);
        assertRefused(AlertRules.resolve(resolved, SUPERVISOR), Refusal.INVALID_STATE);
    }
}
