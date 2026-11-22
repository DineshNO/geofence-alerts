package io.geoalert.alerts.domain;

/**
 * The alert lifecycle as a state machine, with no database and no framework. Given the alert as
 * it is right now (read under a row lock) and who is asking, it decides what happens. Nothing here
 * writes anything: the caller applies the decision.
 *
 * <pre>
 *   OPEN --claim--> CLAIMED --resolve(note)--> RESOLVED
 *   OPEN <-release- CLAIMED
 * </pre>
 */
public final class AlertRules {

    /** Why a command is refused; the web layer turns each into its code and status. */
    public enum Refusal {
        /** another user owns the alert (409 ALERT_ALREADY_CLAIMED) */
        ALREADY_CLAIMED,
        /** the caller is not the owner and not a supervisor (403 NOT_ALERT_OWNER) */
        NOT_OWNER,
        /** the alert is not in a state that allows the command (409 INVALID_STATE) */
        INVALID_STATE
    }

    public sealed interface Decision {
        /** The command changes the alert. */
        record Apply() implements Decision {}

        /** A repeat that changes nothing: answered with the alert as it is, and no audit entry. */
        record NoChange() implements Decision {}

        record Refuse(Refusal reason, String message) implements Decision {}
    }

    private static final Decision APPLY = new Decision.Apply();
    private static final Decision NO_CHANGE = new Decision.NoChange();

    private AlertRules() {}

    /** OPEN to CLAIMED; the caller becomes the owner. */
    public static Decision claim(Alert alert, Actor actor) {
        return switch (alert.status()) {
            case OPEN -> APPLY;
            case CLAIMED ->
                    alert.claimedBy().equals(actor.username())
                            ? NO_CHANGE
                            : new Decision.Refuse(
                                    Refusal.ALREADY_CLAIMED,
                                    "The alert is already claimed by " + alert.claimedBy() + ".");
            case RESOLVED ->
                    new Decision.Refuse(Refusal.INVALID_STATE, "The alert is already resolved.");
        };
    }

    /** CLAIMED to OPEN; the alert has no owner afterwards. Owner or supervisor. */
    public static Decision release(Alert alert, Actor actor) {
        if (alert.status() != AlertStatus.CLAIMED) {
            return new Decision.Refuse(Refusal.INVALID_STATE, "The alert is not claimed.");
        }
        if (!isOwnerOrSupervisor(alert, actor)) {
            return new Decision.Refuse(
                    Refusal.NOT_OWNER, "Only the owner or a supervisor can release the alert.");
        }
        return APPLY;
    }

    /** CLAIMED to RESOLVED. Owner or supervisor. A repeat by the same user changes nothing. */
    public static Decision resolve(Alert alert, Actor actor) {
        return switch (alert.status()) {
            case OPEN ->
                    new Decision.Refuse(
                            Refusal.INVALID_STATE, "The alert is open; claim it first.");
            case RESOLVED ->
                    actor.username().equals(alert.resolvedBy())
                            ? NO_CHANGE
                            : new Decision.Refuse(
                                    Refusal.INVALID_STATE,
                                    "The alert was already resolved by " + alert.resolvedBy() + ".");
            case CLAIMED ->
                    isOwnerOrSupervisor(alert, actor)
                            ? APPLY
                            : new Decision.Refuse(
                                    Refusal.NOT_OWNER,
                                    "Only the owner or a supervisor can resolve the alert.");
        };
    }

    private static boolean isOwnerOrSupervisor(Alert alert, Actor actor) {
        return actor.supervisor() || actor.username().equals(alert.claimedBy());
    }
}
