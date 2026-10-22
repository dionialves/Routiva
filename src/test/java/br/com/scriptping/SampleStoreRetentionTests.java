package br.com.scriptping;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:retention;DB_CLOSE_DELAY=-1", "zabbix.url=", "zabbix.username=", "zabbix.password=", "spring.datasource.username=sa", "spring.datasource.password=", "monitor.retention-hours=48"})
class SampleStoreRetentionTests {
    @Autowired SampleStore store;
    @Autowired JdbcTemplate jdbc;

    @org.junit.jupiter.api.BeforeEach void clearSamples(){jdbc.update("DELETE FROM ping_sample");}

    @Test void configuredRuleDeletesOnlySamplesOlderThan48Hours() {
        long now=System.currentTimeMillis();
        insert("old-response", now-Duration.ofHours(49).toMillis(), 10d, null);
        insert("old-failure", now-Duration.ofHours(48).toMillis()-5000, null, "Tempo esgotado");
        insert("recent-response", now-Duration.ofHours(48).toMillis()+60000, 12d, null);
        insert("recent-failure", now-1000, null, "Tempo esgotado");
        store.cleanup();
        assertThat(jdbc.queryForList("SELECT host_id FROM ping_sample ORDER BY host_id",String.class))
            .containsExactly("recent-failure","recent-response");
    }


    @Test void retentionCanBeChangedThroughConfiguration() {
        long now=System.currentTimeMillis();
        insert("custom-old",now-Duration.ofHours(3).toMillis(),10d,null);
        insert("custom-recent",now-Duration.ofHours(1).toMillis(),10d,null);
        new SampleStore(jdbc,2,600).cleanup();
        assertThat(jdbc.queryForList("SELECT host_id FROM ping_sample WHERE host_id LIKE 'custom-%' ORDER BY host_id",String.class))
            .containsExactly("custom-recent");
    }
    @Test void retentionNeverExceeds48HoursEvenWithHigherConfiguration() {
        long now=System.currentTimeMillis();
        insert("over-limit",now-Duration.ofHours(49).toMillis(),10d,null);
        insert("within-limit",now-Duration.ofHours(47).toMillis(),10d,null);
        new SampleStore(jdbc,168,600).cleanup();
        assertThat(jdbc.queryForList("SELECT host_id FROM ping_sample ORDER BY host_id",String.class))
            .containsExactly("within-limit");
    }

    @Test void rejectsNonPositiveRetentionOrCleanupInterval() {
        org.assertj.core.api.Assertions.assertThatThrownBy(()->new SampleStore(jdbc,0,600)).isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->new SampleStore(jdbc,48,0)).isInstanceOf(IllegalArgumentException.class);
    }

    private void insert(String hostId,long at,Double latency,String error) {
        jdbc.update("INSERT INTO ping_sample(host_id,target,measured_at,latency,error) VALUES(?,?,?,?,?)",hostId,"127.0.0.1",at,latency,error);
    }
}
