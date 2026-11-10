package br.com.scriptping.availability;

import br.com.scriptping.zabbix.Host;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.assertj.core.api.Assertions.*;

/** Opt-in synthetic load, isolated H2 database; never uses production data. */
@EnabledIfSystemProperty(named="availability.benchmark", matches="true")
class AvailabilitySnapshotBenchmarkTests extends MinuteTestSupport {
    @Test void fiveHundredHostsWithDayOfMinutesAndTwoYearsOfDailyHistory() {
        var hosts=java.util.stream.IntStream.rangeClosed(1,500).mapToObj(i ->
            new Host("bench-"+i,"Host "+i,"10.0."+(i/256)+"."+(i%256),
                List.of(new Host.Group("g"+(i%10),"Group "+(i%10))))).toList();
        jdbc=org.mockito.Mockito.spy(jdbc);
        startAggregation();
        queries=new PacketLossAnalysisService(summaries,new PacketLossPolicy(),60000);
        // 720,000 minute rows, 12,000 hourly rows and 365,000 daily rows.
        jdbc.update("""
            INSERT INTO ping_minute
            SELECT 'bench-'||h.X,'10.0.'||CAST(FLOOR(h.X/256.0) AS INT)||'.'||MOD(h.X,256),
                ?-m.X*60000,60,58,1,1,0,590,?-m.X*60000+59000
            FROM SYSTEM_RANGE(1,500) h CROSS JOIN SYSTEM_RANGE(1,1440) m
            """,end,end);
        long day=Math.floorDiv(end,86400000L)*86400000L;
        jdbc.update("""
            INSERT INTO ping_rollup
            SELECT 'bench-'||h.X,'10.0.'||CAST(FLOOR(h.X/256.0) AS INT)||'.'||MOD(h.X,256),
                86400000,?-d.X*86400000,86400,83520,1440,1440,0,849600,?-d.X*86400000+86399000,1440
            FROM SYSTEM_RANGE(1,500) h CROSS JOIN SYSTEM_RANGE(1,730) d
            """,day,day);
        jdbc.update("""
            INSERT INTO ping_rollup
            SELECT 'bench-'||h.X,'10.0.'||CAST(FLOOR(h.X/256.0) AS INT)||'.'||MOD(h.X,256),
                3600000,?-m.X*3600000,3600,3480,60,60,0,35400,?-m.X*3600000+3599000,60
            FROM SYSTEM_RANGE(1,500) h CROSS JOIN SYSTEM_RANGE(1,24) m
            """,end,end);
        jdbc.update("UPDATE ping_aggregation_progress SET processed_at=?,complete_through=?,rollups_ready=TRUE WHERE id=1",now,end);
        org.mockito.Mockito.clearInvocations(jdbc);
        System.gc();
        long heapBefore=java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        long started=System.nanoTime();
        var snapshot=queries.buildAt(hosts,AvailabilityDashboard.MINUTES,now);
        long buildMillis=(System.nanoTime()-started)/1_000_000;
        long selectCount=org.mockito.Mockito.mockingDetails(jdbc).getInvocations().stream()
            .filter(call -> call.getMethod().getName().equals("query") && call.getMethod().getParameterCount()==3
                && call.getMethod().getParameterTypes()[1]==org.springframework.jdbc.core.RowCallbackHandler.class
                || call.getMethod().getName().equals("queryForObject") && call.getMethod().getParameterCount()==2).count()+2;
        org.mockito.Mockito.clearInvocations(jdbc);
        System.gc();
        long heapDelta=java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed()-heapBefore;
        assertThat(selectCount).isLessThanOrEqualTo(52);
        var repository=new AvailabilitySnapshotRepository(jdbc,new ObjectMapper());
        started=System.nanoTime();
        var encoded=repository.encode(snapshot);
        repository.save(snapshot,encoded);
        long persistMillis=(System.nanoTime()-started)/1_000_000;
        started=System.nanoTime();
        var restored=repository.load();
        long restoreMillis=(System.nanoTime()-started)/1_000_000;
        var dayView=restored.view(1440,hosts);
        assertThat(dayView.report().total().total()).isEqualTo(500L*1440*60);
        assertThat(dayView.report().observedMillis()).isEqualTo(86400000);
        assertThat(restored.view(1051200,hosts).report().total().total()).isGreaterThan(dayView.report().total().total());
        started=System.nanoTime();
        for(int i=0;i<100;i++) restored.view(i%2==0?1440:1051200,hosts.subList(0,250));
        long combineMicros=(System.nanoTime()-started)/1000/100;
        var incrementalTimes=new ArrayList<Long>();
        var previous=restored;
        for (int cycle=1;cycle<=3;cycle++) {
            jdbc.update("""
                INSERT INTO ping_sample(host_id,target,measured_at,latency,latency_limit)
                SELECT 'bench-'||X,'10.0.'||CAST(FLOOR(X/256.0) AS INT)||'.'||MOD(X,256),?,10,20
                FROM SYSTEM_RANGE(1,500)
                """,end+(cycle-1)*60000L+1000);
            summaries.aggregate(boundary(),1000,now+cycle*60000L);
            started=System.nanoTime();
            previous=queries.advanceAt(hosts,previous,now+cycle*60000L);
            repository.save(previous,repository.encode(previous));
            incrementalTimes.add((System.nanoTime()-started)/1_000_000);
        }
        assertThat(previous.view(1440,hosts).report().total().healthy()).isEqualTo(500L*((1440-3)*58+3));
        assertThat(incrementalTimes).allSatisfy(ms -> assertThat(ms).isLessThan(60000));
        System.out.println("AVAILABILITY_INCREMENTAL_BENCHMARK cycles_ms="+incrementalTimes);
        System.out.printf("AVAILABILITY_BENCHMARK hosts=500 periods=%d build_ms=%d persist_ms=%d restore_ms=%d gzip_bytes=%d combine_250_hosts_us=%d select_count=%d heap_delta_bytes=%d%n",
            snapshot.periods().size(),buildMillis,persistMillis,restoreMillis,encoded.bytes(),combineMicros,selectCount,heapDelta);
    }
}
