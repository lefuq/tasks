package ru.tz.emulator.config;

import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.tz.emulator.application.*;
import ru.tz.emulator.postgres.JdbcRegistry;

@Configuration
public class Components {
    @Bean Clock clock() { return Clock.systemUTC(); }
    @Bean ExecutionPolicy policy() {
        return new ExecutionPolicy((origin, bound) -> ThreadLocalRandom.current().nextInt(origin, bound));
    }
    @Bean Registry registry(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        return new JdbcRegistry(jdbc, new TransactionTemplate(manager));
    }
    @Bean Operations operations(Registry registry, Clock clock, ExecutionPolicy policy) {
        return new Operations(registry, clock, policy);
    }
    @Bean FaultResponses faultResponses() { return new FaultResponses(); }
    @Bean(destroyMethod = "shutdownNow") ScheduledExecutorService delays() {
        return Executors.newSingleThreadScheduledExecutor();
    }
}
