package ru.tz.exchange;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.tz.exchange.adapter.worker.WorkerClient;
import ru.tz.exchange.application.Ports;
import ru.tz.exchange.domain.*;

class WorkerClientTest {
  @Test
  void createsPostAndEncodedGetWithFixedTimeout() throws Exception {
    HttpClient http = mock(HttpClient.class);
    HttpResponse<String> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(202);
    when(response.body()).thenReturn("ack");
    when(http.sendAsync(
            any(HttpRequest.class),
            org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
        .thenReturn(CompletableFuture.completedFuture(response));
    WorkerClient client = new WorkerClient(new ObjectMapper(), http);
    Operation operation = new Operation();
    operation.name = "Task";
    operation.description = "";
    operation.type = Task.Type.process;
    operation.key = "key";
    Ports.Worker worker = new Ports.Worker(1, "Worker", "http://worker/one");
    assertThat(client.request(operation, worker, true).status()).isEqualTo(202);
    operation.externalId = "a/b c";
    client.request(operation, worker, false);
    ArgumentCaptor<HttpRequest> requests = ArgumentCaptor.forClass(HttpRequest.class);
    verify(http, times(2))
        .sendAsync(
            requests.capture(),
            org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
    assertThat(requests.getAllValues().get(0).headers().firstValue("Idempotency-Key"))
        .contains("key");
    assertThat(requests.getAllValues().get(0).timeout()).contains(Duration.ofSeconds(5));
    assertThat(requests.getAllValues().get(1).uri().toString()).endsWith("/operations/a%2Fb%20c");
  }

  @Test
  void networkFailureBecomesRetryableReply() {
    HttpClient http = mock(HttpClient.class);
    when(http.sendAsync(
            any(HttpRequest.class),
            org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
        .thenReturn(CompletableFuture.failedFuture(new java.io.IOException()));
    WorkerClient client = new WorkerClient(new ObjectMapper(), http);
    Operation operation = new Operation();
    operation.externalId = "id";
    assertThat(
            client
                .request(operation, new Ports.Worker(1, "Worker", "http://worker"), false)
                .networkFailure())
        .isTrue();
  }
}
