package br.com.scriptping.availability;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** One committed generation; replacement rolls back as a unit on any failure. */
@Repository
public class AvailabilitySnapshotRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AvailabilitySnapshotRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc=jdbc; this.mapper=mapper;
    }

    public record Encoded(String payload, int bytes) {}

    public Encoded encode(AvailabilitySnapshot snapshot) {
        try {
            var bytes=new ByteArrayOutputStream();
            try (var gzip=new GZIPOutputStream(bytes)) { mapper.writeValue(gzip, snapshot); }
            return new Encoded(Base64.getEncoder().encodeToString(bytes.toByteArray()), bytes.size());
        } catch (IOException e) { throw new IllegalStateException("Could not encode availability snapshot", e); }
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void save(AvailabilitySnapshot snapshot, Encoded encoded) {
        jdbc.update("DELETE FROM availability_snapshot WHERE id=1");
        jdbc.update("INSERT INTO availability_snapshot(id,format_version,built_at,payload) VALUES(1,?,?,?)",
            snapshot.format(), snapshot.builtAt(), encoded.payload());
    }

    public AvailabilitySnapshot load() {
        var payloads=jdbc.query("SELECT payload FROM availability_snapshot WHERE id=1 AND format_version=?",
            (rs, row) -> rs.getString(1), AvailabilitySnapshot.FORMAT);
        if (payloads.isEmpty()) return null;
        try (var gzip=new GZIPInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(payloads.getFirst())))) {
            var snapshot=mapper.readValue(gzip, AvailabilitySnapshot.class);
            if (snapshot.format()!=AvailabilitySnapshot.FORMAT || !snapshot.periods().keySet().containsAll(AvailabilityDashboard.MINUTES))
                throw new IllegalStateException("Unsupported or incomplete availability snapshot");
            for (var period : snapshot.periods().values()) for (var host : snapshot.hosts()) {
                var data=period.hosts().get(host.id());
                if (data==null || data.points().size()!=period.intervals().size())
                    throw new IllegalStateException("Incomplete host availability snapshot");
            }
            return snapshot;
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException("Could not restore availability snapshot", e);
        }
    }
}
