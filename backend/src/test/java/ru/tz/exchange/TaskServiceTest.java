package ru.tz.exchange;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import ru.tz.exchange.adapter.postgres.JsonEncoding;
import ru.tz.exchange.application.*;
import ru.tz.exchange.domain.*;

class TaskServiceTest {
  Ports.Store store;
  TaskService service;
  Task task;
  Operation operation;
  Actor author = new Actor(1, 1, Actor.Role.INITIATOR, "Author");
  Actor operator = new Actor(10, 1, Actor.Role.OPERATOR, "Operator");
  Instant now = Instant.EPOCH;
  Ports.Transactions transactions =
      new Ports.Transactions() {
        public <T> T run(Supplier<T> action) {
          return action.get();
        }
      };
  Ports.Encoding encoding = new JsonEncoding(new ObjectMapper().findAndRegisterModules());

  @BeforeEach
  void setup() {
    store = mock(Ports.Store.class);
    when(store.taskTypes()).thenReturn(List.of(
        new Ports.TaskType(Task.Type.process, "Обработка"),
        new Ports.TaskType(Task.Type.research, "Исследование"),
        new Ports.TaskType(Task.Type.collect, "Сбор")));
    service = new TaskService(store, transactions, () -> now, UUID::randomUUID, encoding);
    task = Task.create(author, "Task", "", Task.Type.process);
    task.id = 1;
    task.assign(10);
    when(store.task(1, true)).thenReturn(task);
    when(store.task(1, false)).thenReturn(task);
    when(store.worker(anyLong()))
        .thenAnswer(call -> new Ports.Worker(call.getArgument(0), "Worker", "http://worker"));
  }

  TaskService.View command(Actor actor, String action, Long worker) {
    return service.command(
        actor,
        1,
        action,
        UUID.randomUUID().toString(),
        new TaskService.Command(task.version, task.description, task.type, worker));
  }

  void executing() {
    task.workerId = 1L;
    task.send(operator, UUID.randomUUID(), null);
    operation = Operation.start(task, task.activeOperationId, "permanent", now);
    when(store.operation(operation.id)).thenReturn(operation);
  }

  @Test
  void creationAuditsNewBeforeAssignment() {
    when(store.insert(any()))
        .thenAnswer(
            call -> {
              Task task = call.getArgument(0);
              task.id = 2;
              return task;
            });
    when(store.leastLoadedOperator(1)).thenReturn(20L);
    var view = service.create(author, "create", "Name", "", Task.Type.process);
    assertThat(view.operatorId()).isEqualTo(20);
    verify(store).audit(2L, null, now, "assigned", "Назначен оператор #20");
    var order = inOrder(store);
    order.verify(store).lockUser(1);
    order.verify(store).replay(eq(1L), eq("create"), anyString());
    order.verify(store).lockService(1);
    order.verify(store).insert(any());
    order.verify(store).audit(eq(2L), eq(1L), eq(now), eq("created"), contains("NEW"));
    order.verify(store).leastLoadedOperator(1);
  }

  @Test
  void replayReturnsSavedResponseWithoutBusinessEffect() {
    TaskService.View initial = service.get(author, 1);
    when(store.replay(eq(1L), eq("same"), anyString())).thenReturn(encoding.encode(initial));
    assertThat(service.create(author, "same", "Task", "", Task.Type.process)).isEqualTo(initial);
    verify(store, never()).insert(any());
    verify(store, never()).remember(anyLong(), anyString(), anyString(), anyString());
  }

  @Test
  void sendPersistsSnapshotAndQueueBeforeReturning() {
    command(operator, "worker", 1L);
    var view = command(operator, "send", null);
    var captor = org.mockito.ArgumentCaptor.forClass(Operation.class);
    verify(store).save(captor.capture());
    Operation snapshot = captor.getValue();
    assertThat(snapshot.id).isEqualTo(view.operationId());
    assertThat(snapshot.key).isNotBlank();
    assertThat(snapshot.description).isEqualTo(task.description);
    verify(store).enqueue(snapshot.id, "POST", now);
  }

  @Test
  void retryKeepsOperationKeyAndWorkerSwitchStartsFreshBudget() {
    executing();
    operation.problem = Operation.Problem.EXHAUSTED;
    operation.temporaryFailure = true;
    operation.externalId = "external";
    command(operator, "worker", 2L);
    assertThat(operation.workerId).isEqualTo(2);
    assertThat(operation.key).isEqualTo("permanent");
    assertThat(operation.problem).isNull();
    verify(store).cancel(operation.id);
    verify(store).enqueue(operation.id, "GET", now);
  }

  @Test
  void manualRetryPreservesKeyAndRestartsQueue() {
    executing();
    operation.problem = Operation.Problem.INVALID_RESULT;
    command(operator, "retry", null);
    assertThat(operation.key).isEqualTo("permanent");
    assertThat(operation.problem).isNull();
    verify(store).enqueue(operation.id, "POST", now);
  }

  @Test
  void lostResultCreatesDifferentOperationAndKey() {
    executing();
    operation.problem = Operation.Problem.LOST_RESULT;
    var view = command(operator, "send", null);
    var captor = org.mockito.ArgumentCaptor.forClass(Operation.class);
    verify(store).save(captor.capture());
    assertThat(captor.getValue().key).isNotEqualTo(operation.key);
    assertThat(view.operationId()).isNotEqualTo(operation.id);
    verify(store).cancel(operation.id);
  }

  @Test
  void acceptsOnlyManuallyConfirmedMismatch() {
    executing();
    operation.externalId = "x";
    operation.result = new ExternalResult("x", "success", 10, "QQ==");
    operation.problem = Operation.Problem.MISMATCH;
    task.received();
    assertThatThrownBy(() -> command(operator, "accept", null)).isInstanceOf(Failure.class);
    operation.confirmedResult = new ExternalResult("x", "fail", 11, "");
    assertThat(command(operator, "accept", null).state()).isEqualTo(Task.State.COMPLETED);
    assertThat(operation.result.status()).isEqualTo("fail");
    verify(store).resolve(operation.id, 10, now, "accepted");
  }

  @Test
  void returnAndUnchangedEditPreserveOperator() {
    executing();
    operation.problem = Operation.Problem.INVALID_RESULT;
    command(operator, "return", null);
    command(author, "edit", null);
    assertThat(task.state).isEqualTo(Task.State.WAITING_TO_SEND);
    assertThat(task.operatorId).isEqualTo(10);
    verify(store).cancel(operation.id);
  }

  @Test
  void backlogUsesFreshLoadForEveryAssignment() {
    Task first = Task.create(author, "First", "", Task.Type.process);
    first.id = 2;
    Task second = Task.create(author, "Second", "", Task.Type.process);
    second.id = 3;
    when(store.unassigned(1)).thenReturn(List.of(first, second));
    when(store.leastLoadedOperator(1)).thenReturn(10L, 20L);
    service.assignBacklog(1);
    assertThat(first.operatorId).isEqualTo(10);
    assertThat(second.operatorId).isEqualTo(20);
  }

  @Test
  void versionConflictPreventsMutationAndQueue() {
    assertThatThrownBy(
            () ->
                service.command(
                    operator,
                    1,
                    "send",
                    "stale",
                    new TaskService.Command(0, "", Task.Type.process, null)))
        .isInstanceOf(Failure.class);
    verify(store, never()).save(any(Task.class));
    verify(store, never()).enqueue(any(), anyString(), any());
  }

  @Test
  void reconciliationAndRetryKeepVersion() {
    executing();
    long version = task.version;
    command(operator, "reconcile", null);
    assertThat(task.version).isEqualTo(version);
    operation.problem = Operation.Problem.EXHAUSTED;
    command(operator, "retry", null);
    assertThat(task.version).isEqualTo(version);
    verify(store).enqueue(operation.id, "RECONCILE", now);
    verify(store).enqueue(operation.id, "POST", now);
  }

  @Test
  void displaysTypeNameFromDatabase() {
    when(store.taskTypes()).thenReturn(List.of(new Ports.TaskType(Task.Type.process, "Название из БД")));
    assertThat(service.get(author, 1).typeName()).isEqualTo("Название из БД");
  }
}
