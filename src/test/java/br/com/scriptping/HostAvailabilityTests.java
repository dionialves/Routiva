package br.com.scriptping;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class HostAvailabilityTests {

    @Test
    void validatesTenThousandExampleAndPrecision() {
        var counts = new HostAvailabilityService.Counts();
        for (int i = 0; i < 9500; i++) counts.add(PingSample.SUCCESS, 20d, 20);
        for (int i = 0; i < 300; i++) counts.add(PingSample.SUCCESS, 21d, 20);
        for (int i = 0; i < 200; i++) counts.add(PingSample.LOSS, null, 20);
        var m = counts.metrics();
        assertThat(m.technical()).isEqualTo(98);
        assertThat(m.operational()).isEqualTo(95);
        assertThat(m.loss()).isEqualTo(2);
        assertThat(m.degradation()).isEqualTo(3);
        var high = new HostAvailabilityService.Counts();
        for (int i = 0; i < 9999; i++) high.add(PingSample.SUCCESS, 1d, 20);
        high.add(PingSample.LOSS, null, 20);
        assertThat(high.metrics().operational()).isEqualTo(99.99);
    }

    @Test
    void queriesOnlyPeriodAndTargetPreservesThresholdAndExcludesCollectorErrors() {
        var ds = new DriverManagerDataSource(
            "jdbc:h2:mem:availability;DB_CLOSE_DELAY=-1",
            "sa",
            ""
        );
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        var jdbc = new JdbcTemplate(ds);
        var service = new HostAvailabilityService(jdbc);
        var store = new SampleStore(jdbc, 48, 600);
        var host = new Host("availability-test", "Host", "127.0.0.1", java.util.List.of());
        jdbc.update(
            "INSERT INTO host_latency_policy(host_id,latency_limit) VALUES(?,?)",
            host.id(),
            1000
        );
        store.save(host, new PingProbe.Result(21d, null)); // degraded at original 20 ms
        store = new SampleStore(jdbc, 48, 600, event -> {}, 100);
        store.save(host, new PingProbe.Result(21d, null));
        store.save(host, new PingProbe.Result(null, "Tempo esgotado"));
        store.save(host, new PingProbe.Result(null, "Falha ao executar ping"));
        long now = System.currentTimeMillis() + 1;
        jdbc.update(
            "INSERT INTO ping_sample(host_id,target,measured_at,latency) VALUES(?,?,?,?)",
            host.id(),
            host.ip(),
            now - 49 * 3600000L,
            1
        );
        jdbc.update(
            "INSERT INTO ping_sample(host_id,target,measured_at,latency) VALUES(?,?,?,?)",
            host.id(),
            "10.0.0.1",
            now - 1,
            1
        );
        var report = service.report(host, 48, now);
        assertThat(report.total().total()).isEqualTo(3);
        assertThat(report.total().healthy()).isEqualTo(1);
        assertThat(report.total().degraded()).isEqualTo(1);
        assertThat(report.total().lost()).isEqualTo(1);
        assertThat(
            report
                .series()
                .stream()
                .mapToLong(p -> p.metrics().total())
                .sum()
        ).isEqualTo(3);
        assertThat(report.series()).anySatisfy(p -> {
            assertThat(p.metrics().state()).isEqualTo("SEM_DADOS");
            assertThat(p.metrics().operational()).isNull();
        });
        assertThat(
            service
                .report(new Host("empty", "Empty", "127.0.0.1", java.util.List.of()), 1, now)
                .total()
                .technical()
        ).isNull();
        assertThatThrownBy(() ->
            new SampleStore(jdbc, 48, 600, event -> {}, Double.NaN)
        ).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.report(host, 3, now)).isInstanceOf(
            IllegalArgumentException.class
        );
    }
}
