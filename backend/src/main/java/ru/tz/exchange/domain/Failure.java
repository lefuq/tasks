package ru.tz.exchange.domain;

public final class Failure extends RuntimeException {
  public final String code;

  public Failure(String code) {
    super(code);
    this.code = code;
  }

  public static void require(boolean condition, String code) {
    if (!condition) throw new Failure(code);
  }
}
