package br.com.scriptping;

import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;

abstract class MinuteTestSupport {
    DriverManagerDataSource ds;
    JdbcTemplate jdbc;
    DataSourceTransactionManager manager;
    MinuteAggregationStore summaries;
    SampleStore samples;
    PacketLossAnalysisService queries;
    String postgresSchema;
    final long now=java.time.Instant.parse("2026-10-29T16:00:37Z").toEpochMilli();
    final long end=Math.floorDiv(now,60000)*60000;
    final Host host=new Host("a","Host A","127.0.0.1",List.of(new Host.Group("g","Grupo")));
    @BeforeEach void database() {
        String postgres=System.getenv("ROUTIVA_TEST_JDBC_URL");
        if(postgres==null) ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","");
        else {
            postgresSchema="test_"+UUID.randomUUID().toString().replace("-","");
            var admin=new DriverManagerDataSource(postgres,"postgres","routiva-test-only");
            new JdbcTemplate(admin).execute("CREATE SCHEMA "+postgresSchema);
            ds=new DriverManagerDataSource(postgres+"?currentSchema="+postgresSchema,"postgres","routiva-test-only");
        }
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        jdbc=new JdbcTemplate(ds);manager=new DataSourceTransactionManager(ds);
        summaries=new MinuteAggregationStore(jdbc,manager);
        samples=new SampleStore(jdbc,48,600);
        queries=new PacketLossAnalysisService(summaries,new PacketLossPolicy(),48,60000);
    }
    @org.junit.jupiter.api.AfterEach void dropTestSchema() {
        if(postgresSchema!=null) new JdbcTemplate(ds).execute("DROP SCHEMA "+postgresSchema+" CASCADE");
    }
    long insert(Host h,long at,Double latency,String error,double limit) {
        jdbc.update("INSERT INTO ping_sample(host_id,target,measured_at,latency,error,latency_limit) VALUES(?,?,?,?,?,?)",h.id(),h.ip(),at,latency,error,limit);
        return jdbc.queryForObject("SELECT MAX(id) FROM ping_sample",Long.class);
    }
    long insert(long at,Double latency,String error) {return insert(host,at,latency,error,20);}
    long boundary() {return samples.committedBoundary();}
    void drain(int pageSize) {
        long boundary=boundary();
        do {summaries.aggregate(boundary,pageSize,now);} while(summaries.progress().cursor()<boundary);
    }
    long count(String column) {return jdbc.queryForObject("SELECT COALESCE(SUM("+column+"),0) FROM ping_minute",Long.class);}
}
