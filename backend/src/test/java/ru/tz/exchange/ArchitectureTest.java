package ru.tz.exchange;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class ArchitectureTest {
  @Test
  void dependenciesPointInward() {
    var classes = new ClassFileImporter().importPackages("ru.tz.exchange");
    noClasses()
        .that()
        .resideInAPackage("..domain..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "org.springframework..",
            "com.fasterxml..",
            "..adapter..",
            "..application..",
            "..config..",
            "java.sql..",
            "jakarta..")
        .check(classes);
    noClasses()
        .that()
        .resideInAPackage("..adapter.http..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("..adapter.postgres..", "..adapter.worker..")
        .check(classes);
    noClasses()
        .that()
        .resideInAPackage("..application..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "org.springframework..", "..adapter..", "..config..", "java.sql..", "jakarta..")
        .check(classes);
  }
}
