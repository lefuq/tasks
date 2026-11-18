package ru.tz.emulator.application;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Function;
import ru.tz.emulator.domain.*;

public interface Registry {
    Operation register(Operation candidate);
    Operation operation(UUID id);
    Settings settings(String worker);
    void settings(String worker, Settings settings);
    int completeDue(Instant now, Function<Operation, Operation> completion);
}
