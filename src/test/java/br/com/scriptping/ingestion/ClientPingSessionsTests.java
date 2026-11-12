package br.com.scriptping.ingestion;

import br.com.scriptping.collector.proto.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;

class ClientPingSessionsTests {
    private Result result(String id, long at, Status status) {
        return Result.newBuilder().setHostId(id).setTarget("192.0.2.15")
            .setScheduledAt(at * 1000000).setRttNs(2500000).setStatus(status).build();
    }
    @Test void sessionStartsEmptyAndOnlyIpv4IsAccepted() {
        var sessions = new ClientPingSessions();
        for (String ip : List.of("::1", "example.com", "0.0.0.0", "999.1.1.1"))
            assertThatThrownBy(() -> sessions.start("host", "owner", ip)).isInstanceOf(ResponseStatusException.class);
        var view = sessions.start("host", "owner", "192.0.2.15");
        assertThat(view.samples()).isEmpty();
        assertThat(sessions.configurations(1000, 1500, 20)).singleElement().satisfies(config -> {
            assertThat(config.getHostId()).isEqualTo(view.id());
            assertThat(config.getTarget()).isEqualTo("192.0.2.15");
        });
    }
    @Test void distinguishesIcmpLossFromCollectorErrorsAndIgnoresReplaysBeforeOpening() {
        var sessions = new ClientPingSessions();
        var view = sessions.start("host", "owner", "192.0.2.15");
        long at = view.startedAt();
        var batch = Batch.newBuilder().addResults(result(view.id(), at - 1, Status.SUCCESS))
            .addResults(result(view.id(), at, Status.SUCCESS))
            .addResults(result(view.id(), at + 1, Status.TIMEOUT))
            .addResults(result(view.id(), at + 2, Status.COLLECTION_ERROR)).build();
        sessions.accept(List.of(batch, batch));
        var points = sessions.read(view.id(), "host", "owner").samples();
        assertThat(points).hasSize(3);
        assertThat(points.get(0).latency()).isEqualTo(2.5);
        assertThat(points.get(0).loss()).isZero();
        assertThat(points.get(1).latency()).isNull();
        assertThat(points.get(1).loss()).isEqualTo(100);
        assertThat(points.get(2).loss()).isNull();
    }
    @Test void stopsExpiresAndIsolatesOwnersAndFreshModalSessions() {
        var sessions = new ClientPingSessions();
        var first = sessions.start("host", "owner", "192.0.2.15");
        assertThatThrownBy(() -> sessions.read(first.id(), "host", "other")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> sessions.stop(first.id(), "other-host", "owner")).isInstanceOf(ResponseStatusException.class);
        sessions.stop(first.id(), "host", "owner");
        sessions.accept(List.of(Batch.newBuilder().addResults(result(first.id(), first.startedAt(), Status.SUCCESS)).build()));
        var second = sessions.start("host", "owner", "192.0.2.15");
        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(second.samples()).isEmpty();
        sessions.expire(second.startedAt() + 15001);
        assertThat(sessions.configurations(1000, 1500, 20)).isEmpty();
        assertThatThrownBy(() -> sessions.read(second.id(), "host", "owner")).isInstanceOf(ResponseStatusException.class);
    }
    @Test void sessionBufferIsBoundedAndOldReplaysDoNotEvictRecentSamples() {
        var sessions = new ClientPingSessions();
        var view = sessions.start("host", "owner", "192.0.2.15");
        var batch = Batch.newBuilder();
        for (int i = 0; i < 130; i++) batch.addResults(result(view.id(), view.startedAt() + i, Status.SUCCESS));
        sessions.accept(List.of(batch.build()));
        sessions.accept(List.of(Batch.newBuilder().addResults(result(view.id(), view.startedAt(), Status.TIMEOUT)).build()));
        var points = sessions.read(view.id(), "host", "owner").samples();
        assertThat(points).hasSize(120);
        assertThat(points.getFirst().at()).isEqualTo(view.startedAt() + 10);
    }
}
