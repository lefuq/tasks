package ru.tz.exchange.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.tz.exchange.adapter.postgres.*;
import ru.tz.exchange.adapter.worker.WorkerClient;
import ru.tz.exchange.application.*;

@Configuration
public class Components {
  @Bean
  Ports.Directory directory(JdbcTemplate jdbc) {
    return new JdbcDirectory(jdbc);
  }

  @Bean
  Administration administration(
      Ports.Directory directory,
      Ports.Store store,
      Ports.Transactions transactions,
      TaskService tasks) {
    return new Administration(directory, store, transactions, tasks);
  }

  @Bean
  Ports.Encoding encoding(ObjectMapper mapper) {
    return new JsonEncoding(mapper);
  }

  @Bean
  Ports.Store store(JdbcTemplate jdbc, Ports.Encoding encoding) {
    return new JdbcStore(jdbc, encoding);
  }

  @Bean
  Ports.Time time() {
    return Instant::now;
  }

  @Bean
  Ports.Keys keys() {
    return UUID::randomUUID;
  }

  @Bean
  Ports.Jitter jitter() {
    return () -> ThreadLocalRandom.current().nextInt(2, 6);
  }

  @Bean
  RetryPolicy retryPolicy(Ports.Jitter jitter) {
    return new RetryPolicy(jitter);
  }

  @Bean
  Ports.Transactions transactions(PlatformTransactionManager manager) {
    TransactionTemplate template = new TransactionTemplate(manager);
    return new Ports.Transactions() {
      public <T> T run(Supplier<T> action) {
        return template.execute(status -> action.get());
      }
    };
  }

  @Bean
  Ports.External external(ObjectMapper mapper) {
    return new WorkerClient(mapper);
  }

  @Bean
  TaskService taskService(
      Ports.Store store,
      Ports.Transactions transactions,
      Ports.Time time,
      Ports.Keys keys,
      Ports.Encoding encoding) {
    return new TaskService(store, transactions, time, keys, encoding);
  }

  @Bean
  ExchangeService exchangeService(
      Ports.Store store,
      Ports.Transactions transactions,
      Ports.Time time,
      Ports.Keys keys,
      Ports.External external,
      Ports.Encoding encoding,
      RetryPolicy retries) {
    return new ExchangeService(store, transactions, time, keys, external, encoding, retries);
  }
}
