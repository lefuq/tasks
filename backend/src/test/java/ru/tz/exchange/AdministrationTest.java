package ru.tz.exchange;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import ru.tz.exchange.application.*;
import ru.tz.exchange.domain.Actor;
import ru.tz.exchange.domain.Failure;

class AdministrationTest {
  Ports.Directory directory = mock(Ports.Directory.class);
  Ports.Store store = mock(Ports.Store.class);
  TaskService tasks = mock(TaskService.class);
  Ports.Transactions transactions =
      new Ports.Transactions() {
        public <T> T run(Supplier<T> action) {
          return action.get();
        }
      };
  Administration administration = new Administration(directory, store, transactions, tasks);

  @Test
  void operatorCreationLocksServiceAndAssignsBacklog() {
    when(directory.addUser(1, "operator", "Operator", "hash", Actor.Role.OPERATOR)).thenReturn(10L);
    assertThat(administration.createOperator(1, "operator", "Operator", "hash")).isEqualTo(10);
    var order = inOrder(store, directory, tasks);
    order.verify(store).lockService(1);
    order.verify(directory).addUser(1, "operator", "Operator", "hash", Actor.Role.OPERATOR);
    order.verify(tasks).assignBacklog(1);
  }

  @Test
  void bootstrapRejectsInvalidEndpointsAndExistingState() {
    assertThatThrownBy(() -> administration.bootstrap("hash", "file:///tmp/a", "http://two"))
        .isInstanceOf(Failure.class);
    when(directory.empty()).thenReturn(false);
    assertThatThrownBy(() -> administration.bootstrap("hash", "http://one", "http://two"))
        .isInstanceOf(Failure.class);
    verify(directory, never()).addService(anyString());
  }
}
