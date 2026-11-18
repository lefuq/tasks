package ru.tz.exchange;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.tz.exchange.adapter.worker.WorkerClient;
import ru.tz.exchange.domain.Failure;

class ResultContractTest {
    private final WorkerClient client = new WorkerClient(new ObjectMapper());

    @ParameterizedTest
    @ValueSource(strings = {"null", "true", "\"12\"", "[]", "{}"})
    @DisplayName("B-T020 / B-R020 / B-P08: processing_time must be a JSON integer")
    void rejectsNonIntegerDuration(String duration) {
        String response = "{\"id\":\"x\",\"status\":\"success\",\"processing_time\":"
                + duration + ",\"data\":\"QQ==\"}";
        assertThatThrownBy(() -> client.result(response)).isInstanceOf(Failure.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "true", "12", "[]", "{}"})
    @DisplayName("B-T020 / B-R020 / B-P08: data must be a JSON string")
    void rejectsNonStringData(String data) {
        String response = "{\"id\":\"x\",\"status\":\"success\",\"processing_time\":12,\"data\":"
                + data + "}";
        assertThatThrownBy(() -> client.result(response)).isInstanceOf(Failure.class);
    }
}
