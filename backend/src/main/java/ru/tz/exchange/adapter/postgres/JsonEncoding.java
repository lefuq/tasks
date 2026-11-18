package ru.tz.exchange.adapter.postgres;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.tz.exchange.application.Ports;

public final class JsonEncoding implements Ports.Encoding {
  private final ObjectMapper mapper;

  public JsonEncoding(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public String encode(Object object) {
    try {
      return mapper.writeValueAsString(object);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException(exception);
    }
  }

  public <T> T decode(String text, Class<T> type) {
    try {
      return mapper.readValue(text, type);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
