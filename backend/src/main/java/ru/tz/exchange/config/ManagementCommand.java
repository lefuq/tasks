package ru.tz.exchange.config;

import static ru.tz.exchange.domain.Failure.require;

import org.springframework.boot.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import ru.tz.exchange.application.Administration;
import ru.tz.exchange.domain.Failure;

@Component
public class ManagementCommand implements ApplicationRunner {
  private final Environment environment;
  private final Administration administration;
  private final PasswordEncoder passwords;
  private final ConfigurableApplicationContext context;

  public ManagementCommand(
      Environment environment,
      Administration administration,
      PasswordEncoder passwords,
      ConfigurableApplicationContext context) {
    this.environment = environment;
    this.administration = administration;
    this.passwords = passwords;
    this.context = context;
  }

  private String required(String key) {
    String value = environment.getProperty(key);
    require(value != null && !value.isBlank(), "MissingCommandParameter:" + key);
    return value;
  }

  public void run(ApplicationArguments arguments) {
    String command = environment.getProperty("app.command");
    if (command == null) return;
    switch (command) {
      case "bootstrap" ->
          administration.bootstrap(
              passwords.encode(required("APP_COMMAND_PASSWORD")),
              required("app.worker-one-url"),
              required("app.worker-two-url"));
      case "create-operator" ->
          administration.createOperator(
              Long.parseLong(required("app.service-id")),
              required("app.username"),
              required("app.name"),
              passwords.encode(required("APP_COMMAND_PASSWORD")));
      case "demo-baseline" -> administration.demoBaseline();
      case "load-data" -> administration.loadData(required("app.username"));
      default -> throw new Failure("UnknownCommand");
    }
    context.close();
  }
}
