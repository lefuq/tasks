package ru.tz.exchange.domain;

import static ru.tz.exchange.domain.Failure.require;

import java.util.Base64;

public record ExternalResult(String id, String status, long processingTime, String data) {
  public ExternalResult {
    require(id != null && !id.isBlank(), "InvalidResult");
    require("success".equals(status) || "fail".equals(status), "InvalidResult");
    require(processingTime >= 0 && data != null && data.length() <= 136536, "InvalidResult");
    try {
      byte[] bytes = Base64.getDecoder().decode(data);
      require(
          bytes.length <= 102400 && Base64.getEncoder().encodeToString(bytes).equals(data),
          "InvalidResult");
    } catch (IllegalArgumentException exception) {
      throw new Failure("InvalidResult");
    }
  }

  public byte[] bytes() {
    return Base64.getDecoder().decode(data);
  }
}
