# План проверок: emulator

Матрица требований и связей с реализованными проверками. Наличие теста не означает успешного выполнения; результаты формируются при запуске проверок.

ID задач E-Pnn — ссылки на нумерованные этапы `agent-tasks/emulator-plan.md`; исходный план не изменён. ID требований и тестов локальны для системы тестирования.

Реализации, сборки и OpenAPI доступны. Связи с исходниками приведены ниже. PostgreSQL — только отдельный тестовый Docker-контейнер без fallback.

| Требование | Задача | Тест | Тип | Приоритет | Наблюдаемое требование | Вход и действия | Ожидаемый результат |
|---|---|---|---|---|---|---|---|
| E-R001 | E-P04 | E-T001 | integration | P1 | Приём | POST с ключом k1 и name=A,description=,type=process | 202 с ID без ожидания исполнения |
| E-R002 | E-P04 | E-T002 | integration | P1 | Повтор на другом воркере | Отправить одинаковое тело и k1 двум воркерам | Одинаковый ID; одно исполнение |
| E-R003 | E-P04 | E-T003 | integration | P1 | Конфликт ключа | После k1/name=A отправить k1/name=B | 409; исходный снимок не изменён |
| E-R004 | E-P04 | E-T004 | integration | P1 | Конкурентный приём | 20 одновременных одинаковых POST с k1 через два воркера | Один ID; одно действительное исполнение |
| E-R005 | E-P06 | E-T005 | integration | P1 | Неизвестная операция | GET с отсутствующим допустимым ID | 404 |
| E-R006 | E-P06 | E-T006 | integration | P1 | Выполняющаяся операция | GET сразу после приёма до завершения | 202 |
| E-R007 | E-P06 | E-T007 | integration | P1 | Готовый success | Принудительно success; дождаться завершения; GET | 200; совпадающий ID; success; целая неотрицательная длительность; корректный Base64 ≤102400 байт |
| E-R008 | E-P06 | E-T008 | integration | P1 | Готовый fail | Принудительно fail; дождаться завершения; GET | 200; fail; пустая data |
| E-R009 | E-P05 | E-T009 | integration | P1 | Реальное исполнение | Новая операция; наблюдать готовность без перезапуска | Исполнение занимает 10–60 секунд; POST не ждёт завершения |
| E-R010 | E-P05 | E-T010 | integration | P1 | Повтор не сбрасывает таймер | Длительность 30 с через управляемые часы; повтор на 20-й секунде | Готовность на 30-й секунде исходного исполнения |
| E-R011 | E-P05 | E-T011 | integration | P1 | Учёт простоя | Старт t=0; остановка t=5; возобновление t=40; завершение t=40 | processing_time=40; прежний ID; одно исполнение |
| E-R012 | E-P07 | E-T012 | integration | P1 | Потеря GET-ответа | Сохранить результат; включить потерю ответа; повторить GET после снятия сбоя | Первый ответ потерян; сохранённый результат доступен; счётчик не увеличен |
| E-R013 | E-P07 | E-T013 | integration | P1 | Сохранение настроек | Включить воспроизводимый сбой; перезапустить | Настройка сохранилась; после снятия обычное поведение |
| E-R014 | E-P08 | E-T014 | acceptance | P1 | Задержанный ответ | Через механизм бэкенда удержать A до новой операции B | B не перезаписана ответом A; доставка механизма проверена при таймауте 5 с |

## Условия выполнения

Unit: доступные доменные/компонентные интерфейсы, управляемые часы и генератор случайности. Integration: согласованный контракт, собранные компоненты и изолированная PostgreSQL. Acceptance: общий запуск, демонстрационные пользователи, реальный эмулятор и команды сбоев.

Моки допускаются только на внешних границах изолированных тестов. Сквозное исполнение и счётчики проверяются на реальном эмуляторе. Прерывание процесса, перезапуск и сетевые сбои требуют управляемого тестового окружения.

## Дополнение по согласованным уточнениям

Источник: [CLARIFICATIONS.md](../CLARIFICATIONS.md). Условия выполнения — как у основной матрицы.

| Требование | Задача | Тест | Тип | Приоритет | Требование | Вход | Ожидаемый результат |
|---|---|---|---|---|---|---|---|
| E-R015 | E-P05 | E-T015 | unit | P1 | Отбрасывание миллисекунд | started_at=2026-11-14T00:00:00.900Z; finished_at=2026-11-14T00:00:11.100Z | processing_time=11 |
| E-R016 | E-P04 | E-T016 | integration | P1 | Поломанный JSON | POST /operations с Idempotency-Key=k-invalid и телом { | 400; операция не создана |
| E-R017 | E-P04 | E-T017 | integration | P1 | Отсутствующие поля | POST с новым ключом; по отдельности удалить name, description, type из корректного тела | 422; операция не создана |

## Исполняемые проверки

Ссылки на существующие тесты не меняют ожидаемое поведение матрицы. Если проверка охватывает лишь часть строки, оставшееся условие не считается подтверждённым.

| Тест / требование | Реализация |
|---|---|
| E-T001 / E-R001 | [HttpTest#postIs202OnBothEndpoints](../../emulator/src/test/java/ru/tz/emulator/HttpTest.java) |
| E-T002 / E-R002 | [OperationsTest#repeatKeepsIdTimerResultAndCountAcrossEndpoints](../../emulator/src/test/java/ru/tz/emulator/OperationsTest.java) |
| E-T003 / E-R003 | [OperationsTest#conflictDoesNotChangeOriginalSnapshot](../../emulator/src/test/java/ru/tz/emulator/OperationsTest.java) |
| E-T004 / E-R004 | [EmulatorIT#twentyConcurrentRequestsAcrossWorkersHaveOneExecutionAndConflictKeepsSnapshot](../../emulator/src/test/java/ru/tz/emulator/EmulatorIT.java) |
| E-T005 / E-R005 | [OperationsTest#missingKeyAndUnknownOperation](../../emulator/src/test/java/ru/tz/emulator/OperationsTest.java) |
| E-T006 / E-R006 | [HttpTest#getSeparatesPendingAndCompletedAndAllowsAnyWorker](../../emulator/src/test/java/ru/tz/emulator/HttpTest.java) |
| E-T007 / E-R007 | [EmulatorIT#retryDoesNotRestartTimerAndBothBusinessOutcomesAreSaved](../../emulator/src/test/java/ru/tz/emulator/EmulatorIT.java) |
| E-T008 / E-R008 | [EmulatorIT#retryDoesNotRestartTimerAndBothBusinessOutcomesAreSaved](../../emulator/src/test/java/ru/tz/emulator/EmulatorIT.java) |
| E-T009 / E-R009 | [EmulatorIT#realExecutionWaitsWhilePostAndRepeatsRemainFast](../../emulator/src/test/java/ru/tz/emulator/EmulatorIT.java) |
| E-T010 / E-R010 | [EmulatorIT#retryDoesNotRestartTimerAndBothBusinessOutcomesAreSaved](../../emulator/src/test/java/ru/tz/emulator/EmulatorIT.java) |
| E-T011 / E-R011 | [EmulatorIT#pendingUnknownRepeatAndRestartPreserveTimelineAndIncludeDowntime](../../emulator/src/test/java/ru/tz/emulator/EmulatorIT.java) |
| E-T012 / E-R012 | [EmulatorIT#savedResultSurvivesRealFiveSecondClientTimeoutAndRetryOnOtherWorker](../../emulator/src/test/java/ru/tz/emulator/EmulatorIT.java) |
| E-T013 / E-R013 | [EmulatorIT#faultsPersistAcrossRestartAndNeverCorruptIndependentInspection](../../emulator/src/test/java/ru/tz/emulator/EmulatorIT.java) |
| E-T014 / E-R014 | [backend/src/test/java/ru/tz/exchange/JointIT.java](../../backend/src/test/java/ru/tz/exchange/JointIT.java) |
| E-T015 / E-R015 | [DomainTest#truncatesEachTimestampBeforeSubtractionAndIncludesDowntime](../../emulator/src/test/java/ru/tz/emulator/DomainTest.java) |
| E-T016 / E-R016 | [HttpTest#malformedAndMissingFieldsDoNotCreateOperations](../../emulator/src/test/java/ru/tz/emulator/HttpTest.java) |
| E-T017 / E-R017 | [HttpTest#malformedAndMissingFieldsDoNotCreateOperations](../../emulator/src/test/java/ru/tz/emulator/HttpTest.java) |

## Команды запуска из корня проекта

```sh
mvn -f emulator/pom.xml verify
```

Обёртка `python3 testing/emulator/tests/run.py` запускает быстрый набор без БД. Ошибка запуска или отсутствие компонента дают ненулевой код, не skip.

## E-R018 — полный алфавит имени

Задача E-P04; тест E-T018; unit; P1. Вход: каждый из 131 разрешённого символа отдельно. Ожидание: имя допустимо и сохраняется без изменения. Негативные случаи — E-T017 и DomainTest.rejectsInvalidNames; границы длины — DomainTest.rejectsLongName. Сохранение в БД этим unit-тестом не проверяется.

Исходник: [NameContractTest](../../emulator/src/test/java/ru/tz/emulator/NameContractTest.java).

## Дополнительные требования плана

| Требование | Задача | Тест | Тип | Приоритет | Условие | Ожидаемый результат | Реализация |
|---|---|---|---|---|---|---|---|
| E-R019 | E-P03 | E-T019 | integration | P1 | Перезапуск PostgreSQL | Результат и настройки сохраняются после перезапуска контейнера | [EmulatorIT#postgresRestartPreservesResultsAndConfiguration](../../emulator/src/test/java/ru/tz/emulator/EmulatorIT.java) |
| E-R020 | E-P07 | E-T020 | unit | P1 | Невалидная доставка | Искажение ответа не повреждает сохранённый результат | [FaultResponsesTest#corruptionsAffectOnlyDeliveryAndLeaveSavedResultUntouched](../../emulator/src/test/java/ru/tz/emulator/FaultResponsesTest.java) |
| E-R021 | E-P07 | E-T021 | unit | P1 | Управляемый исход | Принудительный success/fail не зависит от типа задания | [OperationsTest#forcedOutcomeIsIndependentOfType](../../emulator/src/test/java/ru/tz/emulator/OperationsTest.java) |
| E-R022 | E-P05 | E-T022 | unit | P1 | Вероятности по умолчанию | Для 100 управляемых исходов генератора: 20 fail и 80 success | [DomainTest#randomnessHasCorrectBoundsAndTwentyPercentFail](../../emulator/src/test/java/ru/tz/emulator/DomainTest.java) |
| E-R023 | E-P09 | E-T023 | unit | P1 | Снятие сбоя | Недоступность блокирует публичные запросы, инспекция и reset работают | [OperationsTest#unavailableBlocksPublicRequestsButAllowsInspectionAndReset](../../emulator/src/test/java/ru/tz/emulator/OperationsTest.java) |

## QA-01: аудит связей 16 ноября 2026

В `traceability.json` у связей E-* указаны фактический уровень (`type`), полнота (`coverage`) и границы (`scope`). MockMvc с mock Operations и OperationsTest с mock Registry считаются unit, даже когда исходная строка требует integration. Дополнительная связь `partial` подтверждает только утверждения указанного метода.

- E-R001/002/003/005/006: HTTP и mock-проверки дополнены конкретными методами EmulatorIT с PostgreSQL. Долговечность не следует из имени `acceptsWithDurableTimingAndOutcome`: этот unit проверяет только сформированные поля операции.
- E-R010: `retryDoesNotRestartTimerAndBothBusinessOutcomesAreSaved` буквально использует готовность t=30 и повтор t=20; tick в t=20 равен 0, в t=30 равен 2 для двух операций. Здесь числового расхождения с матрицей нет.
- E-R011: `pendingUnknownRepeatAndRestartPreserveTimelineAndIncludeDowntime` останавливает процессы в t=20, возобновляет в t=40 при ready_at=30. Проверяет processing_time=40, прежний ID, execution_count=1 и неизменность после ещё одного рестарта. Это прямое свидетельство учёта простоя, но пример остановки t=5 буквально не выполнялся.
- E-R018: `acceptsEveryPermittedCharacter` — 131 параметризованный символ; `rejectsInvalidNames` — 8 недопустимых имён; `rejectsLongName` — 101 символ. Негативные случаи дополняют алфавит, а не доказывают сохранение в БД.
- E-R017: `validatesAndNormalizesSubmission` дополнительно проверяет trim, длины 100/101 и 1000/1001, три допустимых типа и other; это доменная проверка. HTTP missing fields отдельно проверяется `malformedOrMissingFieldsNeverReachRegistryAndConstraintsRejectInvalidState` с нулём строк после отказов.
- E-R020: FaultResponsesTest перебирает все значения Fault; invalid JSON, неизвестный статус, другой ID, отрицательное/дробное время, Base64 и 102401 байт проверяются разными утверждениями. HttpTest дополняет проверкой фактического тела `{`.
- E-R021: `forcedOutcomeIsIndependentOfType` перебирает три типа только для success. Независимость принудительного fail от всех трёх типов остаётся непокрытой.
- E-R022: 100 управляемых значений генератора доказывают границу 20/80, а не статистическое распределение случайной выборки.
- E-R013/023: дополнения lost_result проверяют running/completed, другой воркер, повтор POST и reset; интеграционный метод также проверяет рестарт. Миграционный метод проверяет сохранность outcome и ограничение fault, а не полный пользовательский сценарий настройки.
- E-R014 остаётся acceptance в backend JointIT; emulator verify его не запускает.

Все дополнительные методы шести тестовых классов перечислены в реестре; связь не является новым свидетельством успешного запуска.

## E-R024 — диагностические журналы без секретов

E-P09 / E-T024, integration (и дополнительный unit), QA-02. `EmulatorIT.fullProcessLogsRetainFaultDiagnosticsWithoutSecrets` перехватывает весь stdout/stderr двух приложений текущего теста; отправляет маркеры пароля, SESSION, CSRF в некорректном JSON и заголовках, воспроизводит 400, 503, invalid_json и timeout lose_response. Проверяет наличие четырёх диагностических событий и отсутствие маркеров. `HttpTest.unexpectedFailureLogsDiagnosticWithoutRequestSecrets` дополнительно передаёт секреты в тексте исключения и заголовках, проверяет диагностическое событие 500. Проверки входа и CSRF самого приложения относятся к backend; эти два метода отдельно весь G02 не закрывают.

E-R007: интеграционный метод проверяет success, processing_time=30, Base64, предел размера и содержимое Lorem ipsum, но не сравнивает result.id явно; эта связь отмечена partial. UUID результата проверяется доменным методом E-R015, что не заменяет проверку поля в HTTP-ответе.
