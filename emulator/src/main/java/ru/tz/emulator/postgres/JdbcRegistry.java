package ru.tz.emulator.postgres;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import ru.tz.emulator.application.Registry;
import ru.tz.emulator.domain.*;

public final class JdbcRegistry implements Registry {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JdbcRegistry(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    public Operation register(Operation candidate) {
        return transactions.execute(status -> {
            jdbc.update("""
                    insert into operations(id, idempotency_key, name, description, type, worker,
                        started_at, ready_at, outcome, execution_count)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
                    on conflict (idempotency_key) do nothing
                    """, candidate.id(), candidate.key(), candidate.submission().name(),
                    candidate.submission().description(), candidate.submission().type(), candidate.worker(),
                    Timestamp.from(candidate.startedAt()), Timestamp.from(candidate.readyAt()), candidate.outcome().name());
            return jdbc.queryForObject("select * from operations where idempotency_key = ?", this::read, candidate.key());
        });
    }

    public Operation operation(UUID id) {
        List<Operation> operations = jdbc.query("select * from operations where id = ?", this::read, id);
        return operations.isEmpty() ? null : operations.getFirst();
    }

    public Settings settings(String worker) {
        List<Settings> settings = jdbc.query("select fault, outcome from worker_settings where worker = ?",
                (row, index) -> new Settings(Settings.Fault.valueOf(row.getString(1)),
                        Settings.Outcome.valueOf(row.getString(2))), worker);
        return settings.isEmpty() ? Settings.NORMAL : settings.getFirst();
    }

    public void settings(String worker, Settings settings) {
        jdbc.update("""
                insert into worker_settings(worker, fault, outcome) values (?, ?, ?)
                on conflict (worker) do update set fault = excluded.fault, outcome = excluded.outcome
                """, worker, settings.fault().name(), settings.outcome().name());
    }

    public int completeDue(Instant now, Function<Operation, Operation> completion) {
        return transactions.execute(status -> {
            List<Operation> pending = jdbc.query("""
                    select * from operations where finished_at is null and ready_at <= ?
                    order by ready_at limit 100 for update skip locked
                    """, this::read, Timestamp.from(now));
            for (Operation operation : pending) {
                Operation completed = completion.apply(operation);
                jdbc.update("update operations set finished_at = ?, data = ? where id = ? and finished_at is null",
                        Timestamp.from(completed.finishedAt()), completed.data(), completed.id());
            }
            return pending.size();
        });
    }

    private Operation read(ResultSet row, int index) throws SQLException {
        Timestamp finished = row.getTimestamp("finished_at");
        return new Operation(row.getObject("id", UUID.class), row.getString("idempotency_key"),
                new Submission(row.getString("name"), row.getString("description"), row.getString("type")),
                row.getString("worker"), row.getTimestamp("started_at").toInstant(),
                row.getTimestamp("ready_at").toInstant(), finished == null ? null : finished.toInstant(),
                Settings.Outcome.valueOf(row.getString("outcome")), row.getInt("execution_count"), row.getString("data"));
    }
}
