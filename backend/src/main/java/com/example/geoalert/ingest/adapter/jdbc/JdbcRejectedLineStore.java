package com.example.geoalert.ingest.adapter.jdbc;

import com.example.geoalert.ingest.domain.RejectedLine;
import com.example.geoalert.ingest.domain.RejectedLineStore;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRejectedLineStore implements RejectedLineStore {

    private final JdbcTemplate jdbc;

    public JdbcRejectedLineStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void saveAll(long batchId, List<RejectedLine> lines) {
        jdbc.batchUpdate(
                "INSERT INTO rejected_lines (batch_id, line_no, code, detail, raw_line)"
                        + " VALUES (?, ?, ?, ?, ?)",
                lines,
                lines.size(),
                (ps, line) -> {
                    ps.setLong(1, batchId);
                    ps.setInt(2, line.lineNo());
                    ps.setString(3, line.code().name());
                    ps.setString(4, line.detail());
                    ps.setString(5, line.rawLine());
                });
    }
}
