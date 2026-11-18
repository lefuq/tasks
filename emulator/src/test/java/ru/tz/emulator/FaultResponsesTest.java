package ru.tz.emulator;

import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ru.tz.emulator.application.FaultResponses;
import ru.tz.emulator.domain.*;
import static org.assertj.core.api.Assertions.*;

class FaultResponsesTest {
    @Test void corruptionsAffectOnlyDeliveryAndLeaveSavedResultUntouched() {
        Operation operation = DomainTest.operation(Settings.Outcome.success);
        Operation.Result result = operation.complete(operation.readyAt(), "bG9yZW0=").result();
        FaultResponses faults = new FaultResponses();
        for (Settings.Fault fault : Settings.Fault.values()) {
            Object body = faults.body(result, fault);
            if (fault == Settings.Fault.invalid_json) assertThat(body).isEqualTo("{");
            else {
                Map<?, ?> fields = (Map<?, ?>) body;
                switch (fault) {
                    case unknown_status -> assertThat(fields.get("status")).isEqualTo("unknown");
                    case wrong_id -> assertThat(fields.get("id")).isNotEqualTo(result.id());
                    case negative_time -> assertThat(fields.get("processing_time")).isEqualTo(-1);
                    case fractional_time -> assertThat(fields.get("processing_time")).isEqualTo(1.5);
                    case invalid_base64 -> assertThatThrownBy(() -> Base64.getDecoder().decode((String) fields.get("data")))
                            .isInstanceOf(IllegalArgumentException.class);
                    case oversized_data -> assertThat(Base64.getDecoder().decode((String) fields.get("data"))).hasSize(102401);
                    default -> assertThat(fields.get("data")).isEqualTo(result.data());
                }
            }
            assertThat(result.data()).isEqualTo("bG9yZW0=");
            assertThat(result.status()).isEqualTo("success");
            assertThat(result.processing_time()).isEqualTo(10);
        }
    }
}
