package io.geoalert.ingest.domain.rules;

import com.fasterxml.jackson.databind.JsonNode;
import io.geoalert.ingest.domain.LineOutcome;
import java.util.Optional;

/**
 * One check on a line that is already known to be a JSON object. A rule looks only at the line,
 * keeps no state and never touches the database, so rules can be added, removed and tested alone.
 *
 * <p>Rules run by ascending {@link #priority()}; the first rule that finds a problem decides the
 * rejection. To add a validation: write a class that implements this interface, give it a
 * priority (see {@link RulePriority}, which leaves gaps for new rules), and add it to
 * {@link io.geoalert.ingest.domain.LineValidator#standard()}. Its place in the order comes from
 * its priority, not from where it sits in the list.
 */
public interface LineRule {

    /** Lower runs first. Rules with the same priority run in the order they were given. */
    int priority();

    /** The rejection if the line breaks this rule, otherwise empty. */
    Optional<LineOutcome.Rejected> check(JsonNode line);
}
