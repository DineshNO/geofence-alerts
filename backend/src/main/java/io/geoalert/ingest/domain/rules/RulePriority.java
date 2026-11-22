package io.geoalert.ingest.domain.rules;

/**
 * The priorities of the standard rules, with gaps so a new rule can go between two of them
 * without renumbering. The order follows the contract: required fields, then coordinates, then
 * the timestamp, then the identifiers.
 */
public final class RulePriority {

    public static final int REQUIRED_FIELDS = 100;
    public static final int COORDINATES = 200;
    public static final int TIMESTAMP = 300;
    public static final int ASSET_ID = 400;
    public static final int REPORT_ID = 500;

    private RulePriority() {}
}
