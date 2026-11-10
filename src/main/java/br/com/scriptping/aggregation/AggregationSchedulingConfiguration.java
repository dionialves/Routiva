package br.com.scriptping.aggregation;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
class AggregationSchedulingConfiguration {

    @Bean
    ThreadPoolTaskScheduler indicatorScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("indicators-");
        return scheduler;
    }
    @Bean
    ThreadPoolTaskScheduler availabilityScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("availability-");
        return scheduler;
    }
}
