package br.com.scriptping.cleanup;

import java.util.ArrayDeque;
import java.util.function.Consumer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
public class CleanupQueue {
    @Bean
    ThreadPoolTaskScheduler cleanupScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("cleanup-");
        return scheduler;
    }

    /** Jobs may append their next small batch after the preceding transaction commits. */
    synchronized void drain(Consumer<Consumer<Runnable>> planner) {
        var queue = new ArrayDeque<Runnable>();
        planner.accept(queue::addLast);
        while (!queue.isEmpty() && !Thread.currentThread().isInterrupted()) {
            queue.removeFirst().run();
        }
    }
}
