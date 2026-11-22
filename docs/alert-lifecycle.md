# Alert lifecycle

An alert is opened by the system when an asset enters a RESTRICTED zone. An operator claims it,
works on it, and resolves it with a note. The rules are in one place without a database or framework:
`AlertRules` (backend/.../alerts/domain), and every command runs under a row lock on the alert.

```
 system detects entry
         |
         v
    +---------+   claim    +----------+   resolve + note   +----------+
    |  OPEN   | ---------> | CLAIMED  | -----------------> | RESOLVED |
    |(no owner)| <--------- | (1 owner)|                    |  (final) |
    +---------+   release  +----------+                    +----------+
```

## Who may do what

| Command | From | To | Allowed for | Refused when | Answer |
|---|---|---|---|---|---|
| open (system) | none | OPEN | the ingest rules | an alert for the same asset and zone is OPEN or CLAIMED (R3) | none, nothing is created |
| claim | OPEN | CLAIMED | any operator or supervisor | already claimed by someone else | 409 `ALERT_ALREADY_CLAIMED`, naming the owner |
| claim | CLAIMED by the same user | unchanged | the owner | | 200, no audit entry (a repeat) |
| claim | RESOLVED | | | | 409 `INVALID_STATE` |
| release | CLAIMED | OPEN | the owner or a supervisor | not claimed (409 `INVALID_STATE`); caller is neither (403 `NOT_ALERT_OWNER`) | 200 |
| resolve | CLAIMED | RESOLVED | the owner or a supervisor, with a note | still OPEN (409 `INVALID_STATE`); caller is neither (403 `NOT_ALERT_OWNER`) | 200 |
| resolve | RESOLVED by the same user | unchanged | | | 200, no audit entry |
| resolve | RESOLVED by another user | | | | 409 `INVALID_STATE` |

After RESOLVED the same asset entering the same zone again opens a **new** alert.

## One owner (R4) and the audit trail (R5)

Each command is one transaction: lock the alert row (`SELECT ... FOR UPDATE`), ask `AlertRules`, apply
the change, write the audit entry, commit. Ten operators claiming at once give one winner and nine
answers naming the winner. The audit entry (who, what, when, from which state to which, the note) is
written by the application in the same transaction, so a change without its entry cannot be committed.
