package io.geoalert.assets;

import static org.assertj.core.api.Assertions.assertThat;

import io.geoalert.assets.domain.SourceType;
import io.geoalert.support.PostgresIntegrationTest;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** The type is derived in Java when writing and in SQL when filtering old rows: they must agree. */
class SourceTypeTest extends PostgresIntegrationTest {

    // the distinct sources of the supplied feed, plus the awkward ones
    private static final List<String> SOURCES =
            Arrays.asList(
                    "SAT-1", "SAT-2", "SAT-3", "GND-1", "GND-9", "MOB-1", "MOB-12", "XYZ-3",
                    "SAT", "GND", "MOB", "sat-1", "gnd-1", "mob-1", " SAT-1", "XSAT-1", "", " ",
                    "S", "SATELLITE-1", null);

    @Autowired private JdbcTemplate jdbc;

    @Test
    void javaAndSqlGiveTheSameTypeForEverySource() {
        for (String source : SOURCES) {
            String sql =
                    jdbc.queryForObject(
                            "SELECT " + SourceType.SQL_DERIVE + " FROM (SELECT ?::text AS source) t",
                            String.class,
                            source);
            assertThat(SourceType.derive(source).name())
                    .as("source '%s'", source)
                    .isEqualTo(sql);
        }
    }

    @Test
    void theRuleIsByPrefixAndCaseSensitive() {
        assertThat(SourceType.derive("SAT-2")).isEqualTo(SourceType.SATELLITE);
        assertThat(SourceType.derive("GND-7")).isEqualTo(SourceType.GROUND);
        assertThat(SourceType.derive("MOB-1")).isEqualTo(SourceType.MOBILE);
        assertThat(SourceType.derive("XYZ-1")).isEqualTo(SourceType.UNKNOWN);
        assertThat(SourceType.derive("sat-1")).isEqualTo(SourceType.UNKNOWN);
        assertThat(SourceType.derive(null)).isEqualTo(SourceType.UNKNOWN);
        assertThat(SourceType.derive("")).isEqualTo(SourceType.UNKNOWN);
    }

    @Test
    void theNewColumnIsNullableSoTheOldVersionStillWorks() {
        String nullable =
                jdbc.queryForObject(
                        "SELECT is_nullable FROM information_schema.columns"
                                + " WHERE table_schema = 'public' AND table_name = 'reports'"
                                + " AND column_name = 'source_type'",
                        String.class);
        assertThat(nullable).isEqualTo("YES");
    }
}
