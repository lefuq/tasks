package ru.tz.emulator.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "ru.tz.emulator")
@EnableScheduling
public class Application {
    public static void main(String[] args) { SpringApplication.run(Application.class, args); }
}
