package ru.tz.exchange.adapter.postgres;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.tz.exchange.application.Ports;
import ru.tz.exchange.domain.Actor;

public final class JdbcDirectory implements Ports.Directory {
  private final JdbcTemplate jdbc;

  public JdbcDirectory(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Ports.Identity identity(String username) {
    List<Ports.Identity> users =
        jdbc.query(
            "select * from users where username=?",
            (r, i) ->
                new Ports.Identity(
                    new Actor(
                        r.getLong("id"),
                        r.getLong("service_id"),
                        Actor.Role.valueOf(r.getString("role")),
                        r.getString("name")),
                    r.getString("username"),
                    r.getString("password_hash")),
            username);
    return users.isEmpty() ? null : users.getFirst();
  }

  public long addService(String name) {
    return jdbc.queryForObject(
        "insert into services(name) values(?) returning id", Long.class, name);
  }

  public long addUser(
      long serviceId, String username, String name, String passwordHash, Actor.Role role) {
    return jdbc.queryForObject(
        "insert into users(service_id,username,name,password_hash,role) values(?,?,?,?,?) returning"
            + " id",
        Long.class,
        serviceId,
        username,
        name,
        passwordHash,
        role.name());
  }

  public void addWorker(String name, String endpoint) {
    jdbc.update("insert into workers(name,endpoint) values(?,?)", name, endpoint);
  }

  public boolean empty() {
    return jdbc.queryForObject("select count(*) from services", Long.class) == 0;
  }
}
