package io.geoalert.ingest.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Every rejection code of the contract, and the rules for optional fields (R6). */
class LineValidatorTest {

    private final LineValidator validator = new LineValidator();

    private static final String VALID =
            """
            {"reportId":"3f9a1c2b7d4e5f60","assetId":"AST-00417","kind":"SURFACE",\
            "observedAt":"2026-11-02T06:00:05.120Z","lat":35.41267,"lon":170.88421,\
            "speedKn":14.2,"headingDeg":271.4,"source":"SAT-2"}""";

    private LineOutcome.Valid valid(String line) {
        LineOutcome outcome = validator.validate(line);
        assertThat(outcome).isInstanceOf(LineOutcome.Valid.class);
        return (LineOutcome.Valid) outcome;
    }

    private RejectionCode rejected(String line) {
        LineOutcome outcome = validator.validate(line);
        assertThat(outcome).isInstanceOf(LineOutcome.Rejected.class);
        return ((LineOutcome.Rejected) outcome).code();
    }

    /** The valid sample with one field replaced by the given JSON text. */
    private static String with(String field, String jsonValue) {
        return VALID.replaceAll(
                "\"" + field + "\":(\"[^\"]*\"|[^,}]+)",
                java.util.regex.Matcher.quoteReplacement("\"" + field + "\":" + jsonValue));
    }

    private static String without(String field) {
        return VALID.replaceAll("\"" + field + "\":(\"[^\"]*\"|[^,}]+),?", "");
    }

    // ---- a valid line

    @Test
    void aValidLineIsParsedWithTypedValues() {
        ParsedReport report = valid(VALID).report();
        assertThat(report.reportId()).isEqualTo("3f9a1c2b7d4e5f60");
        assertThat(report.assetId()).isEqualTo("AST-00417");
        assertThat(report.observedAt()).isEqualTo(Instant.parse("2026-11-02T06:00:05.120Z"));
        assertThat(report.lat()).isEqualTo(35.41267);
        assertThat(report.lon()).isEqualTo(170.88421);
        assertThat(report.kind()).isEqualTo("SURFACE");
        assertThat(report.speedKn()).isEqualTo(14.2);
        assertThat(report.headingDeg()).isEqualTo(271.4);
        assertThat(report.source()).isEqualTo("SAT-2");
        assertThat(valid(VALID).replacedFields()).isEmpty();
    }

    @Test
    void theOptionalFieldsMayBeAbsent() {
        String minimal =
                "{\"reportId\":\"r1\",\"assetId\":\"AST-1\",\"observedAt\":\"2026-11-02T06:00:05Z\","
                        + "\"lat\":1,\"lon\":2}";
        ParsedReport report = valid(minimal).report();
        assertThat(report.kind()).isNull();
        assertThat(report.speedKn()).isNull();
        assertThat(report.headingDeg()).isNull();
        assertThat(report.source()).isNull();
        assertThat(valid(minimal).replacedFields()).isEmpty();
    }

    // ---- MALFORMED_JSON

    @ParameterizedTest
    @ValueSource(strings = {"not json", "{\"reportId\":", "[1,2,3]", "\"text\"", "42", "null", "{} trailing"})
    void aLineThatIsNotOneJsonObjectIsMalformed(String line) {
        assertThat(rejected(line)).isEqualTo(RejectionCode.MALFORMED_JSON);
    }

    // ---- MISSING_FIELD

    @ParameterizedTest
    @ValueSource(strings = {"reportId", "assetId", "observedAt", "lat", "lon"})
    void aMissingRequiredFieldIsRejected(String field) {
        assertThat(rejected(without(field))).isEqualTo(RejectionCode.MISSING_FIELD);
    }

    @ParameterizedTest
    @ValueSource(strings = {"reportId", "assetId", "observedAt", "lat", "lon"})
    void aNullRequiredFieldIsRejected(String field) {
        assertThat(rejected(with(field, "null"))).isEqualTo(RejectionCode.MISSING_FIELD);
    }

    @ParameterizedTest
    @ValueSource(strings = {"reportId", "assetId", "observedAt", "lat", "lon"})
    void anEmptyStringRequiredFieldIsRejectedAsMissing(String field) {
        assertThat(rejected(with(field, "\"\""))).isEqualTo(RejectionCode.MISSING_FIELD);
    }

    // ---- INVALID_COORDINATE

    @ParameterizedTest
    @CsvSource({"lat,90.0001", "lat,-90.0001", "lon,180.0001", "lon,-180.0001", "lat,1e999"})
    void aCoordinateOutOfRangeIsRejected(String field, String value) {
        assertThat(rejected(with(field, value))).isEqualTo(RejectionCode.INVALID_COORDINATE);
    }

    @ParameterizedTest
    @CsvSource({"lat,\"35.4\"", "lon,\"170.8\"", "lat,true", "lon,[1]", "lat,\"north\""})
    void aCoordinateThatIsNotANumberIsRejected(String field, String value) {
        assertThat(rejected(with(field, value))).isEqualTo(RejectionCode.INVALID_COORDINATE);
    }

    @ParameterizedTest
    @CsvSource({"lat,90", "lat,-90", "lon,180", "lon,-180", "lat,0", "lon,0.0"})
    void theCoordinateLimitsThemselvesAreValid(String field, String value) {
        valid(with(field, value));
    }

    // ---- INVALID_TIMESTAMP

    @ParameterizedTest
    @ValueSource(
            strings = {
                "\"yesterday\"",
                "\"2026-13-40T00:00:00Z\"",
                "\"2026-11-02\"",
                "\"2026-11-02T06:00:05\"",
                "1762063205",
                "true",
                "\"+999999999-01-01T00:00:00Z\""
            })
    void aTimestampThatIsNotAnInstantIsRejected(String value) {
        assertThat(rejected(with("observedAt", value))).isEqualTo(RejectionCode.INVALID_TIMESTAMP);
    }

    @Test
    void anOffsetTimestampIsConvertedToUtc() {
        ParsedReport report = valid(with("observedAt", "\"2026-11-02T10:00:05.5+04:00\"")).report();
        assertThat(report.observedAt()).isEqualTo(Instant.parse("2026-11-02T06:00:05.500Z"));
    }

    // ---- INVALID_FIELD

    @ParameterizedTest
    @ValueSource(strings = {"\"ab\"", "\"lower-case1\"", "\"-AST1\"", "\"AST 1\"", "\"AST_1\"", "\"ÄST-1\"", "123"})
    void anAssetIdThatBreaksThePatternIsRejected(String value) {
        assertThat(rejected(with("assetId", value))).isEqualTo(RejectionCode.INVALID_FIELD);
    }

    @Test
    void anAssetIdAtTheLengthLimitsIsValid() {
        valid(with("assetId", "\"ABC\""));
        valid(with("assetId", "\"A" + "B".repeat(31) + "\""));
        assertThat(rejected(with("assetId", "\"A" + "B".repeat(32) + "\"")))
                .isEqualTo(RejectionCode.INVALID_FIELD);
    }

    @Test
    void aReportIdOverTheLimitIsRejectedAndTheLimitItselfIsValid() {
        valid(with("reportId", "\"" + "x".repeat(64) + "\""));
        assertThat(rejected(with("reportId", "\"" + "x".repeat(65) + "\"")))
                .isEqualTo(RejectionCode.INVALID_FIELD);
    }

    @Test
    void aReportIdThatIsNotTextOrHoldsControlCharactersIsRejected() {
        assertThat(rejected(with("reportId", "12345"))).isEqualTo(RejectionCode.INVALID_FIELD);
        // NUL cannot be stored in the database, so it is stopped here
        assertThat(rejected(with("reportId", "\"ab\\u0000cd\""))).isEqualTo(RejectionCode.INVALID_FIELD);
        assertThat(rejected(with("reportId", "\"ab\\ncd\""))).isEqualTo(RejectionCode.INVALID_FIELD);
    }

    // ---- order of the checks

    @Test
    void theFirstProblemInTheContractOrderIsTheOneReported() {
        // missing field wins over a bad coordinate
        String line = without("assetId").replace("\"lat\":35.41267", "\"lat\":999");
        assertThat(rejected(line)).isEqualTo(RejectionCode.MISSING_FIELD);
        // a bad coordinate wins over a bad timestamp
        assertThat(rejected(with("lat", "999").replace("2026-11-02T06:00:05.120Z", "bad")))
                .isEqualTo(RejectionCode.INVALID_COORDINATE);
        // a bad timestamp wins over a bad asset id
        assertThat(rejected(with("assetId", "\"x\"").replace("2026-11-02T06:00:05.120Z", "bad")))
                .isEqualTo(RejectionCode.INVALID_TIMESTAMP);
    }

    // ---- optional fields never reject

    @Test
    void anInvalidOptionalNumberBecomesNullAndTheLineIsKept() {
        LineOutcome.Valid outcome = valid(with("speedKn", "\"fast\""));
        assertThat(outcome.report().speedKn()).isNull();
        assertThat(outcome.report().headingDeg()).isEqualTo(271.4);
        assertThat(outcome.replacedFields()).containsExactly("speedKn");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"NaN\"", "\"Infinity\"", "\"-Infinity\"", "\"14.1kn\"", "\"0x10\"", "\"14.1f\"", "true", "[1]", "{}", "1e999"})
    void numbersThatAreNotUsableBecomeNull(String value) {
        LineOutcome.Valid outcome = valid(with("headingDeg", value));
        assertThat(outcome.report().headingDeg()).isNull();
        assertThat(outcome.replacedFields()).containsExactly("headingDeg");
    }

    @ParameterizedTest
    @CsvSource({"\"14.1\",14.1", "\" 14.1 \",14.1", "\"-3\",-3.0", "\"1e2\",100.0", "\".5\",0.5", "-1.0,-1.0", "720,720.0"})
    void numbersAndNumericStringsAreStoredAsGiven(String value, double expected) {
        // including a negative speed and a heading over 360: odd data, kept as received
        LineOutcome.Valid outcome = valid(with("speedKn", value));
        assertThat(outcome.report().speedKn()).isEqualTo(expected);
        assertThat(outcome.replacedFields()).isEmpty();
    }

    @Test
    void optionalTextIsStoredAsReceived() {
        assertThat(valid(with("source", "\"XYZ-9\"")).report().source()).isEqualTo("XYZ-9");
        assertThat(valid(with("source", "\"\"")).report().source()).isEmpty();
        assertThat(valid(with("kind", "5")).report().kind()).isEqualTo("5");
    }

    @Test
    void optionalTextTheDatabaseCannotHoldBecomesNull() {
        LineOutcome.Valid nul = valid(with("source", "\"SAT\\u0000-2\""));
        assertThat(nul.report().source()).isNull();
        assertThat(nul.replacedFields()).containsExactly("source");

        LineOutcome.Valid object = valid(with("kind", "{\"a\":1}"));
        assertThat(object.report().kind()).isNull();
        assertThat(object.replacedFields()).containsExactly("kind");
    }

    @Test
    void anUnknownExtraFieldIsIgnored() {
        valid(VALID.replace("}", ",\"extra\":{\"x\":1}}"));
    }
}
