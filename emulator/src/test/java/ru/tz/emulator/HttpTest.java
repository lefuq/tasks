package ru.tz.emulator;

import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import ru.tz.emulator.application.*;
import ru.tz.emulator.domain.*;
import ru.tz.emulator.http.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class HttpTest {
    Operations operations;
    ScheduledExecutorService delays;
    MockMvc mvc;
    Operation operation;
    String body = "{\"name\":\"A\",\"description\":\"\",\"type\":\"process\"}";

    @BeforeEach void setup() {
        operations = mock(Operations.class);
        delays = mock(ScheduledExecutorService.class);
        operation = DomainTest.operation(Settings.Outcome.success);
        when(operations.accept(anyString(), any(), any())).thenReturn(operation);
        when(operations.get(anyString(), any())).thenReturn(operation);
        when(operations.settings(anyString())).thenReturn(Settings.NORMAL);
        mvc = MockMvcBuilders.standaloneSetup(new OperationsController(operations, new FaultResponses(), delays, "worker-1"),
                new AdminController(operations)).setControllerAdvice(new Errors()).build();
    }

    @Test void postIs202OnBothEndpoints() throws Exception {
        for (String path : new String[]{"/operations", "/workers/worker-2/operations"}) {
            mvc.perform(post(path).header("Idempotency-Key", "key").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isAccepted()).andExpect(jsonPath("$.id").value(operation.id().toString()));
        }
        verify(operations).accept("worker-2", "key", operation.submission());
    }

    @Test void malformedAndMissingFieldsDoNotCreateOperations() throws Exception {
        for (String malformed : new String[]{"{", "[]", "null", "{\"name\":1,\"description\":\"\",\"type\":\"process\"}",
                "{\"name\":\"A\",\"description\":\"\",\"type\":\"process\",\"worker\":\"x\"}"}) {
            mvc.perform(post("/operations").contentType(MediaType.APPLICATION_JSON).content(malformed))
                    .andExpect(status().isBadRequest());
        }
        for (String missing : new String[]{"{}", "{\"description\":\"\",\"type\":\"process\"}",
                "{\"name\":\"A\",\"type\":\"process\"}", "{\"name\":\"A\",\"description\":\"\"}"}) {
            mvc.perform(post("/operations").contentType(MediaType.APPLICATION_JSON).content(missing))
                    .andExpect(status().isUnprocessableEntity());
        }
        verify(operations, never()).accept(any(), any(), any());
    }

    @Test void propagatesDomainErrorsAndHidesInternalErrors() throws Exception {
        when(operations.accept(any(), any(), any())).thenThrow(new Problem(409, "IdempotencyConflict"));
        mvc.perform(post("/operations").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
        when(operations.get(any(), any())).thenThrow(new Problem(404, "UnknownOperation"));
        mvc.perform(get("/operations/" + operation.id())).andExpect(status().isNotFound());
        doThrow(new IllegalStateException("secret")).when(operations).get(any(), any());
        mvc.perform(get("/operations/" + operation.id())).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("ServerError"));
    }

    @Test void unexpectedFailureLogsDiagnosticWithoutRequestSecrets(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        String password = "qa-password-marker-761";
        String session = "qa-session-marker-762";
        String csrf = "qa-csrf-marker-763";
        doThrow(new IllegalStateException(password + session + csrf)).when(operations).get(any(), any());
        mvc.perform(get("/operations/" + operation.id())
                        .header("Cookie", "SESSION=" + session)
                        .header("X-CSRF-TOKEN", csrf)
                        .header("Authorization", "Bearer " + password))
                .andExpect(status().isInternalServerError());
        assertThat(output.getAll()).contains("Request failed: IllegalStateException")
                .doesNotContain(password, session, csrf);
    }

    @Test void getSeparatesPendingAndCompletedAndAllowsAnyWorker() throws Exception {
        MvcResult pending = mvc.perform(get("/operations/" + operation.id())).andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(pending)).andExpect(status().isAccepted());
        Operation completed = operation.complete(operation.readyAt(), "bG9yZW0=");
        when(operations.get(any(), any())).thenReturn(completed);
        MvcResult ready = mvc.perform(get("/workers/worker-2/operations/" + operation.id())).andReturn();
        mvc.perform(asyncDispatch(ready)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.processing_time").value(10)).andExpect(jsonPath("$.data").value("bG9yZW0="));
        verify(operations).get("worker-2", operation.id());
    }

    @Test void lostResponseIsScheduledOutsideRequestAndCanBeControlledWithoutSleep() throws Exception {
        when(operations.get(any(), any())).thenReturn(operation.complete(operation.readyAt(), "bG9yZW0="));
        when(operations.settings(any())).thenReturn(new Settings(Settings.Fault.lose_response, Settings.Outcome.random));
        MvcResult pending = mvc.perform(get("/operations/" + operation.id())).andExpect(request().asyncStarted()).andReturn();
        var callback = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        verify(delays).schedule(callback.capture(), eq(6L), eq(TimeUnit.SECONDS));
        callback.getValue().run();
        mvc.perform(asyncDispatch(pending)).andExpect(status().isGatewayTimeout());
    }

    @Test void malformedResultIsReallyMalformedJson() throws Exception {
        when(operations.get(any(), any())).thenReturn(operation.complete(operation.readyAt(), "bG9yZW0="));
        when(operations.settings(any())).thenReturn(new Settings(Settings.Fault.invalid_json, Settings.Outcome.random));
        MvcResult response = mvc.perform(get("/operations/" + operation.id())).andReturn();
        mvc.perform(asyncDispatch(response)).andExpect(status().isOk()).andExpect(content().string("{"));
    }

    @Test void adminReadsActualStateAndConfiguresAndResetsFaults() throws Exception {
        when(operations.inspect(operation.id())).thenReturn(operation);
        mvc.perform(get("/admin/operations/" + operation.id())).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("running")).andExpect(jsonPath("$.execution_count").value(1));
        when(operations.inspect(operation.id())).thenReturn(operation.complete(operation.readyAt(), "bG9yZW0="));
        mvc.perform(get("/admin/operations/" + operation.id())).andExpect(jsonPath("$.state").value("completed"))
                .andExpect(jsonPath("$.result.data").value("bG9yZW0="));
        mvc.perform(put("/admin/workers/worker-1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"fault\":\"unavailable\",\"outcome\":\"success\"}")).andExpect(status().isOk());
        verify(operations).configure("worker-1", new Settings(Settings.Fault.unavailable, Settings.Outcome.success));
        mvc.perform(get("/admin/workers/worker-1")).andExpect(jsonPath("$.fault").value("normal"));
        mvc.perform(delete("/admin/workers/worker-1")).andExpect(status().isOk());
        verify(operations).configure("worker-1", Settings.NORMAL);
    }

    @Test void missingAdminSettingsAre422AndInvalidSettingsAre400() throws Exception {
        mvc.perform(put("/admin/workers/worker-1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"fault\":\"normal\"}")).andExpect(status().isUnprocessableEntity());
        mvc.perform(put("/admin/workers/worker-1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"fault\":\"invalid\",\"outcome\":\"random\"}")).andExpect(status().isBadRequest());
        verify(operations, never()).configure(any(), any());
    }
}
