package ru.tz.exchange.application;

import static ru.tz.exchange.domain.Failure.require;

import java.time.Duration;

public final class RetryPolicy {
  private static final int[] DELAYS = {5, 15, 30, 60, 120};
  private final Ports.Jitter jitter;

  public RetryPolicy(Ports.Jitter jitter) {
    this.jitter = jitter;
  }

  public Duration afterFailure(int previousFailures) {
    if (previousFailures >= DELAYS.length) return null;
    require(previousFailures >= 0, "InvalidRetryCount");
    int seconds = jitter.seconds();
    require(seconds >= 2 && seconds <= 5, "InvalidJitter");
    return Duration.ofSeconds(DELAYS[previousFailures] + seconds);
  }
}
