package io.geoalert.ingest.adapter.jdbc;

import io.geoalert.ingest.domain.RejectedLineQueries;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRejectedLineQueries implements RejectedLineQueries {

    private final JdbcTemplate jdbc;

    public JdbcRejectedLineQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<StoredRejection> newestFirst(long beforeId, int limit) {
        return jdbc.query(
                "SELECT id, batch_id, line_no, code, detail, raw_line, created_at FROM rejected_lines"
                        + " WHERE id < ? ORDER BY id DESC LIMIT ?",
                (rs, row) ->
                        new StoredRejection(
                                rs.getLong("id"),
                                rs.getLong("batch_id"),
                                rs.getInt("line_no"),
                                rs.getString("code"),
                                rs.getString("detail"),
                                rs.getString("raw_line"),
                                rs.getObject("created_at", OffsetDateTime.class).toInstant()),
                beforeId,
                limit + 1);
    }
}
