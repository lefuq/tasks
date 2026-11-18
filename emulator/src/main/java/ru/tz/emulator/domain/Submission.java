package ru.tz.emulator.domain;

public record Submission(String name, String description, String type) {
    public Submission {
        if (name == null || description == null || type == null) {
            throw new Problem(422, "MissingFields");
        }
        name = name.strip();
        if (!name.matches("[A-Za-zА-Яа-яЁё0-9_.-]{1,100}") || description.length() > 1000
                || !java.util.Set.of("process", "research", "collect").contains(type)) {
            throw new Problem(500, "ServerError");
        }
    }
}
