package ru.tz.exchange;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import ru.tz.exchange.application.RetryPolicy;

class RetryPolicyTest {
  @Test
  void hasFiveRetriesWithExactDelays() {
    RetryPolicy policy = new RetryPolicy(() -> 2);
    int[] delays = {7, 17, 32, 62, 122};
    for (int index = 0; index < delays.length; index++)
      assertThat(policy.afterFailure(index)).isEqualTo(Duration.ofSeconds(delays[index]));
    assertThat(policy.afterFailure(5)).isNull();
  }
}
