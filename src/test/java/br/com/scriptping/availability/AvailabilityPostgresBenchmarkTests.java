package br.com.scriptping.availability;

import br.com.scriptping.zabbix.Host;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.assertj.core.api.Assertions.*;

/** Opt-in, isolated schema on a dedicated test PostgreSQL, never production. */
@EnabledIfEnvironmentVariable(named="ROUTIVA_TEST_JDBC_URL", matches="jdbc:postgresql:.*")
@EnabledIfSystemProperty(named="availability.postgres-benchmark", matches="true")
class AvailabilityPostgresBenchmarkTests extends MinuteTestSupport {
    @Test void fourAndHalfMillionMinutesAndTwoYearsOfRollups() {
        var hosts=java.util.stream.IntStream.rangeClosed(1,500).mapToObj(i ->
            new Host("load-"+i,"Host "+i,"10.0."+(i/256)+"."+(i%256),List.of())).toList();
        jdbc.update("""
            INSERT INTO ping_minute
            SELECT 'load-'||h,'10.0.'||(h/256)||'.'||(h%256),
                ?-m*60000::bigint,60,58,1,1,0,590,?-m*60000::bigint+59000
            FROM generate_series(1,500) h CROSS JOIN generate_series(1,9000) m
            """,end,end);
        summaries.aggregate(boundary(),1000,now); // Bootstrap the hour/day accelerators once.
        long day=Math.floorDiv(end,86400000L)*86400000L;
        jdbc.update("""
            INSERT INTO ping_rollup
            SELECT 'load-'||h,'10.0.'||(h/256)||'.'||(h%256),86400000,
                ?-d*86400000::bigint,86400,83520,1440,1440,0,849600,?-d*86400000::bigint+86399000,1440
            FROM generate_series(1,500) h CROSS JOIN generate_series(8,730) d
            """,day,day);
        jdbc.execute("ANALYZE ping_minute"); jdbc.execute("ANALYZE ping_rollup");
        var repo=new AvailabilitySnapshotRepository(jdbc,new com.fasterxml.jackson.databind.ObjectMapper());
        long started=System.nanoTime();
        var snapshot=queries.advanceAt(hosts,null,now);
        long initialMillis=(System.nanoTime()-started)/1_000_000;
        repo.save(snapshot,repo.encode(snapshot));
        var timings=new ArrayList<Long>();
        for (int cycle=1;cycle<=5;cycle++) {
            long time=now+cycle*60000L;
            for (var h : hosts) insert(h,end+(cycle-1)*60000L+1000,10d,null,20);
            if (cycle==3) insert(hosts.getFirst(),end-100*86400000L,12d,null,20);
            long limit=boundary();
            summaries.aggregate(limit,1000,time);
            // Resume from the durable payload, as after a restart.
            snapshot=repo.load();
            started=System.nanoTime();
            snapshot=queries.advanceAt(hosts,snapshot,time);
            repo.save(snapshot,repo.encode(snapshot));
            timings.add((System.nanoTime()-started)/1_000_000);
        }
        var expected=queries.buildAt(hosts,AvailabilityDashboard.MINUTES,now+5*60000L);
        for (int period : AvailabilityDashboard.MINUTES) for (var h : hosts) {
            var a=snapshot.periods().get(period).hosts().get(h.id()).total();
            var b=expected.periods().get(period).hosts().get(h.id()).total();
            assertThat(a.healthy()).isEqualTo(b.healthy()); assertThat(a.lost()).isEqualTo(b.lost());
            assertThat(a.validMinutes()).isEqualTo(b.validMinutes()); assertThat(a.lastSampleAt()).isEqualTo(b.lastSampleAt());
            assertThat(a.latencySum()).isCloseTo(b.latencySum(),within(0.000001));
        }
        System.out.printf("POSTGRES_AVAILABILITY_BENCHMARK minute_rows=4500000 hosts=500 periods=16 initial_ms=%d incremental_ms=%s%n",initialMillis,timings);
        assertThat(timings).allSatisfy(ms -> assertThat(ms).isLessThan(60000));
        var plan=jdbc.queryForList("""
            EXPLAIN (ANALYZE, BUFFERS) SELECT host_id,target,SUM(healthy),SUM(lost)
            FROM ping_minute WHERE minute_at>=? AND minute_at<? GROUP BY host_id,target
            """,String.class,end+4*60000L,end+5*60000L);
        plan.forEach(System.out::println);
    }
}
