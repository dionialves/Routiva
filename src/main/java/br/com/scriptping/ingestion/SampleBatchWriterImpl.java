package br.com.scriptping.ingestion;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Collections;
import java.util.List;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

class SampleBatchWriterImpl implements SampleBatchWriter {

    private static final int ROWS_PER_INSERT = 500;
    private static final String INSERT = """
        INSERT INTO ping_sample(host_id, target, measured_at, latency, error, latency_limit,
            collector_id, attempt_id, batch_id, attempt_sequence, scheduled_at_ns, sent_at_ns,
            received_at_ns, rtt_ns, collection_status, error_code)
        """;
    private static final String VALUES = "(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

    private final JdbcTemplate jdbc;

    SampleBatchWriterImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insertSamples(List<Row> rows) {
        if (rows.isEmpty()) return;
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            if ("H2".equals(connection.getMetaData().getDatabaseProductName())) {
                insertH2(connection, rows);
            } else {
                for (int start = 0; start < rows.size(); start += ROWS_PER_INSERT) {
                    int count = Math.min(ROWS_PER_INSERT, rows.size() - start);
                    String sql = INSERT + " VALUES " + String.join(",", Collections.nCopies(count, VALUES))
                        + " ON CONFLICT (collector_id, attempt_id) DO NOTHING";
                    try (var statement = connection.prepareStatement(sql)) {
                        int parameter = 1;
                        for (int i = start; i < start + count; i++) {
                            parameter = bind(statement, parameter, rows.get(i));
                        }
                        statement.executeUpdate();
                    }
                }
            }
            return null;
        });
    }

    // The H2 test database does not support PostgreSQL's targeted ON CONFLICT clause.
    private static void insertH2(Connection connection, List<Row> rows) throws SQLException {
        try (var statement = connection.prepareStatement(INSERT + """
            SELECT ?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?
            WHERE NOT EXISTS (SELECT 1 FROM ping_sample WHERE collector_id = ? AND attempt_id = ?)
            """)) {
            for (var row : rows) {
                int next = bind(statement, 1, row);
                statement.setString(next, row.collectorId());
                statement.setLong(next + 1, row.attemptId());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static int bind(PreparedStatement statement, int index, Row row) throws SQLException {
        statement.setString(index++, row.hostId());
        statement.setString(index++, row.target());
        statement.setLong(index++, row.measuredAt());
        statement.setObject(index++, row.latency(), Types.DOUBLE);
        statement.setString(index++, row.error());
        statement.setDouble(index++, row.latencyLimit());
        statement.setString(index++, row.collectorId());
        statement.setLong(index++, row.attemptId());
        statement.setLong(index++, row.batchId());
        statement.setLong(index++, row.sequence());
        statement.setLong(index++, row.scheduledAt());
        statement.setLong(index++, row.sentAt());
        statement.setLong(index++, row.receivedAt());
        statement.setLong(index++, row.rttNs());
        statement.setString(index++, row.status());
        statement.setString(index++, row.errorCode());
        return index;
    }
}
