package ru.tz.exchange.domain;

public record Actor(long id, long serviceId, Role role, String name) {
  public enum Role {
    INITIATOR,
    OPERATOR
  }
}
