package ru.tz.exchange.adapter.worker;

import static ru.tz.exchange.domain.Failure.require;

import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import ru.tz.exchange.application.Ports;
import ru.tz.exchange.domain.*;

public final class WorkerClient implements Ports.External {
  private final HttpClient client;
  private final ObjectMapper mapper;

  public WorkerClient(ObjectMapper mapper) {
    this(
        mapper,
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build());
  }

  public WorkerClient(ObjectMapper mapper, HttpClient client) {
    this.mapper = mapper.copy().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    this.client = client;
  }

  public Ports.Reply request(Operation operation, Ports.Worker worker, boolean post) {
    CompletableFuture<HttpResponse<String>> pending = null;
    try {
      String path = worker.endpoint().replaceAll("/$", "") + "/operations";
      if (!post)
        path +=
            "/"
                + URLEncoder.encode(operation.externalId, StandardCharsets.UTF_8)
                    .replace("+", "%20");
      HttpRequest.Builder builder =
          HttpRequest.newBuilder(URI.create(path)).timeout(Duration.ofSeconds(5));
      if (post)
        builder
            .header("Idempotency-Key", operation.key)
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    mapper.writeValueAsString(
                        Map.of(
                            "name",
                            operation.name,
                            "description",
                            operation.description,
                            "type",
                            operation.type.name()))));
      else builder.GET();
      pending =
          client.sendAsync(
              builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      HttpResponse<String> response = pending.get(5, TimeUnit.SECONDS);
      return new Ports.Reply(response.statusCode(), response.body(), false);
    } catch (InterruptedException exception) {
      if (pending != null) pending.cancel(true);
      Thread.currentThread().interrupt();
      return new Ports.Reply(0, "RequestInterrupted", true);
    } catch (Exception exception) {
      if (pending != null) pending.cancel(true);
      return new Ports.Reply(0, "NetworkFailure", true);
    }
  }

  private JsonNode json(String body) {
    try {
      JsonNode node = mapper.readTree(body);
      require(node != null && node.isObject(), "InvalidResult");
      return node;
    } catch (java.io.IOException exception) {
      throw new Failure("InvalidResult");
    }
  }

  public String acceptedId(String body) {
    JsonNode node = json(body).get("id");
    require(node != null && node.isTextual() && !node.textValue().isBlank(), "InvalidResult");
    return node.textValue();
  }

  public ExternalResult result(String body) {
    JsonNode node = json(body);
    require(
        node.hasNonNull("id")
            && node.get("id").isTextual()
            && node.hasNonNull("status")
            && node.get("status").isTextual()
            && node.hasNonNull("processing_time")
            && node.get("processing_time").isIntegralNumber()
            && node.get("processing_time").canConvertToLong()
            && node.hasNonNull("data")
            && node.get("data").isTextual(),
        "InvalidResult");
    return new ExternalResult(
        node.get("id").textValue(),
        node.get("status").textValue(),
        node.get("processing_time").longValue(),
        node.get("data").textValue());
  }
}
