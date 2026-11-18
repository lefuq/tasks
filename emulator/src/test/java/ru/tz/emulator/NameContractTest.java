package ru.tz.emulator;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.stream.Stream;
import ru.tz.emulator.domain.*;

class NameContractTest {
    static Stream<String> permittedNames() {
        return "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyzАБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯабвгдеёжзийклмнопрстуфхцчшщъыьэюя0123456789-_.".chars().mapToObj(character -> String.valueOf((char) character));
    }

    @ParameterizedTest
    @MethodSource("permittedNames")
    @DisplayName("E-T018 / E-R018 / E-P04: every permitted name character")
    void acceptsEveryPermittedCharacter(String name) {
        assertThat(new Submission(name, "", "process").name()).isEqualTo(name);
    }
}
