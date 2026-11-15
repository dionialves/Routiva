package br.com.scriptping.maps;

import br.com.scriptping.zabbix.Host;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import static org.assertj.core.api.Assertions.*;

class PopLatencyServiceTests {
    @Test void latestReadingsRespectMembershipMissingDataAndConfiguredLimit() {
        var db = new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build();
        try {
            var jdbc = new NamedParameterJdbcTemplate(db);
            jdbc.getJdbcTemplate().execute("CREATE TABLE ping_sample(id BIGINT,host_id VARCHAR,latency DOUBLE,measured_at BIGINT)");
            long now = System.currentTimeMillis();
            jdbc.getJdbcTemplate().update("INSERT INTO ping_sample VALUES(1,'a',90,?),(2,'a',10,?),(3,'b',40,?),(4,'c',NULL,?),(5,'c',99,?),(6,'d',999,?),(7,'old',80,?)",
                now - 1000, now, now, now, now - 1000, now, now - 120000);
            var group = new Host.Group("g", "POP");
            var hosts = List.of(new Host("a", "A", "a", List.of(group)),
                new Host("b", "B", "b", List.of(group)), new Host("c", "C", "c", List.of(group)),
                new Host("d", "D", "d", List.of()), new Host("old", "Old", "old", List.of(group)));
            var service = new PopLatencyService(jdbc, 30);
            var summary = service.summaries(Set.of("g"), Set.of(), hosts).get("group:g");
            assertThat(summary.average()).isEqualTo(25d);
            assertThat(summary.maximum()).isEqualTo(40d);
            assertThat(summary.aboveLimit()).isEqualTo(1);
            assertThat(summary.hosts()).extracting(PopLatencyService.Reading::id).containsExactly("b", "a", "c");
            assertThat(summary.hosts().get(2).latency()).isNull();
            assertThat(service.state(24d)).isEqualTo("ATENCAO");
            assertThat(service.summaries(Set.of("missing"), Set.of(), hosts).get("group:missing").average()).isNull();
        } finally { db.shutdown(); }
    }
}
