package br.com.scriptping.availability;

import br.com.scriptping.zabbix.Host;

import br.com.scriptping.ingestion.SampleIngestionService;

import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import br.com.scriptping.aggregation.MinuteAggregationService;
import br.com.scriptping.aggregation.MinuteRetention;

abstract class MinuteTestSupport {
    DriverManagerDataSource ds;
    JdbcTemplate jdbc;
    AnnotationConfigApplicationContext context;
    MinuteAggregationService summaries;
    MinuteRetention retention;
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
        jdbc=new JdbcTemplate(ds);
        startAggregation();
        queries=new PacketLossAnalysisService(summaries,new PacketLossPolicy(),48,60000);
    }
    void startAggregation() {
        if (context != null) {
            context.close();
        }
        context = new AnnotationConfigApplicationContext();
        context.register(TransactionConfiguration.class, MinuteAggregationService.class, MinuteRetention.class);
        context.register(SampleIngestionService.class);
        context.registerBean(JdbcTemplate.class, () -> jdbc);
        context.registerBean("entityManagerFactory", LocalContainerEntityManagerFactoryBean.class, () -> {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(ds);
            factory.setPackagesToScan(MinuteAggregationService.class.getPackageName(), SampleIngestionService.class.getPackageName());
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.jdbc.batch_size", 50));
            return factory;
        });
        context.registerBean("transactionManager", JpaTransactionManager.class,
            () -> new JpaTransactionManager(context.getBean(jakarta.persistence.EntityManagerFactory.class)));
        context.refresh();
        summaries = context.getBean(MinuteAggregationService.class);
        retention = context.getBean(MinuteRetention.class);
    }

    @EnableJpaRepositories(basePackageClasses = {MinuteAggregationService.class, SampleIngestionService.class})
    @EnableTransactionManagement
    static class TransactionConfiguration {}

    @org.junit.jupiter.api.AfterEach void dropTestSchema() {
        if (context != null) {
            context.close();
        }
        if(postgresSchema!=null) new JdbcTemplate(ds).execute("DROP SCHEMA "+postgresSchema+" CASCADE");
    }
    long insert(Host h,long at,Double latency,String error,double limit) {
        jdbc.update("INSERT INTO ping_sample(host_id,target,measured_at,latency,error,latency_limit) VALUES(?,?,?,?,?,?)",h.id(),h.ip(),at,latency,error,limit);
        return jdbc.queryForObject("SELECT MAX(id) FROM ping_sample",Long.class);
    }
    long insert(long at,Double latency,String error) {return insert(host,at,latency,error,20);}
    long boundary() {return summaries.committedBoundary();}
    void drain(int pageSize) {
        long boundary=boundary();
        do {summaries.aggregate(boundary,pageSize,now);} while(summaries.progress().cursor()<boundary);
    }
    long count(String column) {return jdbc.queryForObject("SELECT COALESCE(SUM("+column+"),0) FROM ping_minute",Long.class);}
}
