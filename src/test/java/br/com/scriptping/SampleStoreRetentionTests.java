package br.com.scriptping;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:retention;DB_CLOSE_DELAY=-1", "zabbix.url=", "zabbix.username=", "zabbix.password=", "spring.datasource.username=sa", "spring.datasource.password=", "monitor.retention-hours=168"})
class SampleStoreRetentionTests {
    @Autowired SampleStore store;
    @Autowired JdbcTemplate jdbc;

    @Test void provisionalRuleDeletesOnlySamplesOlderThan48HoursEvenWithLegacySetting() {
        long now=System.currentTimeMillis();
        insert("old-response", now-Duration.ofHours(49).toMillis(), 10d, null);
        insert("old-failure", now-Duration.ofHours(48).toMillis()-5000, null, "Tempo esgotado");
        insert("recent-response", now-Duration.ofHours(48).toMillis()+60000, 12d, null);
        insert("recent-failure", now-1000, null, "Tempo esgotado");
        store.cleanup();
        assertThat(jdbc.queryForList("SELECT host_id FROM ping_sample ORDER BY host_id",String.class))
            .containsExactly("recent-failure","recent-response");
    }

    private void insert(String hostId,long at,Double latency,String error) {
        jdbc.update("INSERT INTO ping_sample(host_id,target,measured_at,latency,error) VALUES(?,?,?,?,?)",hostId,"127.0.0.1",at,latency,error);
    }
}
