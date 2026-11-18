package ru.tz.exchange;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.tz.exchange.adapter.worker.WorkerClient;
import ru.tz.exchange.domain.*;

class ResultTest {
  private final WorkerClient client = new WorkerClient(new ObjectMapper());

  @ParameterizedTest
  @ValueSource(
      strings = {
        "not json",
        "{}",
        "[]",
        "null",
        "{\"id\":1}",
        "{\"id\":\"x\",\"status\":\"other\",\"processing_time\":1,\"data\":\"\"}",
        "{\"id\":\"x\",\"status\":\"success\",\"processing_time\":-1,\"data\":\"\"}",
        "{\"id\":\"x\",\"status\":\"success\",\"processing_time\":1.5,\"data\":\"\"}",
        "{\"id\":\"x\",\"status\":\"success\",\"processing_time\":1,\"data\":\"%%%\"}"
      })
  void rejectsInvalidResponses(String response) {
    assertThatThrownBy(() -> client.result(response)).isInstanceOf(Failure.class);
  }

  @Test
  void checksDecodedSizeAndAllowsEmptyFail() {
    assertThat(
            new ExternalResult(
                    "x", "success", 1, Base64.getEncoder().encodeToString(new byte[102400]))
                .bytes())
        .hasSize(102400);
    assertThatThrownBy(
            () ->
                new ExternalResult(
                    "x", "success", 1, Base64.getEncoder().encodeToString(new byte[102401])))
        .isInstanceOf(Failure.class);
    assertThat(new ExternalResult("x", "fail", 0, "").bytes()).isEmpty();
  }

  @Test
  void rejectsWrongIdAndPreservesConflictingObservations() {
    Operation operation = new Operation();
    operation.externalId = "x";
    ExternalResult first = new ExternalResult("x", "success", 10, "QQ==");
    ExternalResult second = new ExternalResult("x", "fail", 11, "");
    assertThat(operation.observe(first, false)).isTrue();
    assertThat(operation.observe(first, false)).isFalse();
    assertThatThrownBy(
            () -> operation.observe(new ExternalResult("other", "success", 1, ""), false))
        .isInstanceOf(Failure.class);
    assertThat(operation.observe(second, false)).isFalse();
    assertThat(operation.result).isEqualTo(first);
    assertThat(operation.acceptableResult()).isNull();
    operation.observe(second, true);
    assertThat(operation.acceptableResult()).isEqualTo(second);
    assertThat(operation.result).isEqualTo(first);
  }

  @Test
  void validatesAcknowledgment() {
    assertThat(client.acceptedId("{\"id\":\"external\"}")).isEqualTo("external");
    assertThatThrownBy(() -> client.acceptedId("{\"id\":3}")).isInstanceOf(Failure.class);
  }

  @Test
  void retryAndTransportErrorsCannotEraseAnUnresolvedMismatch() {
    Operation operation = new Operation();
    operation.externalId = "x";
    ExternalResult first = new ExternalResult("x", "success", 10, "QQ==");
    ExternalResult other = new ExternalResult("x", "fail", 11, "");
    operation.observe(first, false);
    operation.observe(other, true);
    operation.problem = Operation.Problem.TEMPORARY;
    operation.restart();
    operation.observe(first, false);
    assertThat(operation.problem).isEqualTo(Operation.Problem.MISMATCH);
    assertThat(operation.acceptableResult()).isNull();
    operation.observe(first, true);
    assertThat(operation.acceptableResult()).isEqualTo(first);
  }

  @Test
  void firstManualMismatchRequiresAConfirmingReconciliation() {
    Operation operation = new Operation();
    operation.externalId = "x";
    operation.observe(new ExternalResult("x", "success", 10, "QQ=="), false);
    ExternalResult other = new ExternalResult("x", "fail", 11, "");
    operation.observe(other, true);
    assertThat(operation.acceptableResult()).isNull();
    operation.observe(other, true);
    assertThat(operation.acceptableResult()).isEqualTo(other);
  }
}
