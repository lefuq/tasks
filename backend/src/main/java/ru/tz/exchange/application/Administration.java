package ru.tz.exchange.application;

import static ru.tz.exchange.domain.Failure.require;

import java.net.URI;
import ru.tz.exchange.domain.*;

public final class Administration {
  private final Ports.Directory directory;
  private final Ports.Store store;
  private final Ports.Transactions transactions;
  private final TaskService tasks;

  public Administration(
      Ports.Directory directory,
      Ports.Store store,
      Ports.Transactions transactions,
      TaskService tasks) {
    this.directory = directory;
    this.store = store;
    this.transactions = transactions;
    this.tasks = tasks;
  }

  public long createOperator(long serviceId, String username, String name, String passwordHash) {
    require(
        username != null
            && !username.isBlank()
            && name != null
            && !name.isBlank()
            && passwordHash != null
            && !passwordHash.isBlank(),
        "Validation");
    return transactions.run(
        () -> {
          store.lockService(serviceId);
          long id = directory.addUser(serviceId, username, name, passwordHash, Actor.Role.OPERATOR);
          tasks.assignBacklog(serviceId);
          return id;
        });
  }

  private void endpoint(String endpoint) {
    URI uri = URI.create(endpoint);
    require(
        ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
            && uri.getHost() != null
            && uri.getUserInfo() == null
            && uri.getQuery() == null
            && uri.getFragment() == null,
        "Validation");
  }

  public void bootstrap(String passwordHash, String firstEndpoint, String secondEndpoint) {
    endpoint(firstEndpoint);
    endpoint(secondEndpoint);
    transactions.run(
        () -> {
          require(directory.empty(), "AlreadyInitialized");
          long first = directory.addService("Служба А");
          long second = directory.addService("Служба Б");
          directory.addUser(first, "initiator1", "Инициатор 1", passwordHash, Actor.Role.INITIATOR);
          directory.addUser(first, "initiator2", "Инициатор 2", passwordHash, Actor.Role.INITIATOR);
          directory.addUser(first, "operator1", "Оператор 1", passwordHash, Actor.Role.OPERATOR);
          directory.addUser(first, "operator2", "Оператор 2", passwordHash, Actor.Role.OPERATOR);
          directory.addUser(
              second, "initiator3", "Инициатор 3", passwordHash, Actor.Role.INITIATOR);
          directory.addWorker("Исполнитель 1", firstEndpoint);
          directory.addWorker("Исполнитель 2", secondEndpoint);
          return null;
        });
  }

  public void demoBaseline() {
    tasks.create(
        directory.identity("initiator3").actor(), "bootstrap-new", "Новое", "", Task.Type.collect);
    tasks.create(
        directory.identity("initiator1").actor(),
        "bootstrap-waiting",
        "Ожидающее",
        "",
        Task.Type.process);
  }

  public void loadData(String username) {
    Ports.Identity identity = directory.identity(username);
    require(identity != null, "NotFound");
    for (int index = 0; index < 5000; index++)
      tasks.create(identity.actor(), "nf08-seed-" + index, "Load-" + index, "", Task.Type.process);
  }
}
