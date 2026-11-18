package ru.tz.exchange;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.stream.Stream;
import ru.tz.exchange.domain.*;

class NameContractTest {
    static Stream<String> permittedNames() {
        return "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyzАБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯабвгдеёжзийклмнопрстуфхцчшщъыьэюя0123456789-_.".chars().mapToObj(character -> String.valueOf((char) character));
    }

    @ParameterizedTest
    @MethodSource("permittedNames")
    @DisplayName("B-T028 / B-R028 / B-P05: every permitted name character")
    void acceptsEveryPermittedCharacter(String name) {
        assertThat(Task.create(new Actor(1, 1, Actor.Role.INITIATOR, "Author"), name, "", Task.Type.process).name).isEqualTo(name);
    }
}
