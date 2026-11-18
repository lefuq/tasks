package ru.tz.exchange.adapter.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.tz.exchange.application.ExchangeService;

@Component
@ConditionalOnProperty(name = "app.exchange-enabled", havingValue = "true", matchIfMissing = true)
public class ExchangeScheduler {
  private static final Logger LOG = LoggerFactory.getLogger(ExchangeScheduler.class);
  private final ExchangeService exchange;

  public ExchangeScheduler(ExchangeService exchange) {
    this.exchange = exchange;
  }

  @Scheduled(fixedDelay = 250)
  public void run() {
    try {
      exchange.tick();
    } catch (RuntimeException exception) {
      LOG.error("Exchange processing failed: {}", exception.getClass().getSimpleName());
    }
  }
}
