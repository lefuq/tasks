# План проверок: backend

Матрица требований и связей с реализованными проверками. Наличие теста не означает успешного выполнения; результаты формируются при запуске проверок.

ID задач B-Pnn — ссылки на нумерованные этапы `agent-tasks/backend-plan.md`; исходный план не изменён. ID требований и тестов локальны для системы тестирования.

Реализации, сборки и OpenAPI доступны. Связи с исходниками приведены ниже. PostgreSQL — только отдельный тестовый Docker-контейнер без fallback.

| Требование | Задача | Тест | Тип | Приоритет | Наблюдаемое требование | Вход и действия | Ожидаемый результат |
|---|---|---|---|---|---|---|---|
| B-R001 | B-P04 | B-T001 | integration | P1 | Оператор с минимальной нагрузкой | Операторы 10:2 задания, 20:1; создать задание | Назначен 20; состояние Ожидает отправки |
| B-R002 | B-P04 | B-T002 | integration | P1 | Разрешение равной нагрузки | Операторы 10 и 20 имеют по одному заданию | Назначен 10 |
| B-R003 | B-P04 | B-T003 | integration | P1 | Отсутствие оператора | Служба B без оператора; создать задание | Сохранено Новое без назначения |
| B-R004 | B-P04 | B-T004 | integration | P1 | Назначение накопленных заданий | Создать оператор 30 в службе B с новым заданием | Задание назначено 30 |
| B-R005 | B-P04 | B-T005 | unit | P1 | Возвращённое задание входит в нагрузку | 10: одно возвращённое; 20: ноль; создать задание | Назначен 20 |
| B-R006 | B-P04 | B-T006 | unit | P1 | Завершённое задание исключено из нагрузки | 10: одно завершённое; 20: ноль; создать задание | Назначен 10 |
| B-R007 | B-P03 | B-T007 | integration | P1 | Изоляция служб | Пользователь A запрашивает карточку, историю и файл задания B | Доступ запрещён; данные B не раскрыты и не изменены |
| B-R008 | B-P03 | B-T008 | integration | P1 | Изоляция авторов | Инициатор A1 запрашивает задание A2 своей службы | Доступ запрещён, включая историю и файл |
| B-R009 | B-P03 | B-T009 | integration | P1 | Изоляция назначений | Оператор 10 изменяет задание оператора 20 | Изменение отклонено; задание сохранено без изменений |
| B-R010 | B-P05 | B-T010 | unit | P1 | Границы имени | Создать с именами длины 0,1,100,101 ASCII символов | 0 и 101 отклонены; 1 и 100 допустимы |
| B-R011 | B-P05 | B-T011 | unit | P1 | Границы описания | Описание длины 0,1000,1001 ASCII символов | 0 и 1000 допустимы; 1001 отклонено |
| B-R012 | B-P05 | B-T012 | unit | P1 | Тип задания | Типы process,research,collect,other | Первые три допустимы; other отклонён |
| B-R013 | B-P05 | B-T013 | acceptance | P1 | Возврат и сохранение без изменений | Невалидный результат → возврат → Сохранить без изменений | Прежний оператор; Ожидает отправки; следующая отправка с новым ключом; история сохранена |
| B-R014 | B-P05 | B-T014 | acceptance | P1 | Принятие отрицательного результата | Получить fail с пустой data; принять; попытаться изменить | Завершено; изменение отклонено; результат сохранён |
| B-R015 | B-P06 | B-T015 | integration | P1 | Повтор команды | Дважды создать с одним ключом и одинаковым телом | Одно задание; повтор не создаёт бизнес-эффекта |
| B-R016 | B-P06 | B-T016 | integration | P1 | Атомарность сохранения | Прервать сохранение очереди при отправке | Нет частичного бизнес-изменения, аудита или очереди |
| B-R017 | B-P06 | B-T017 | integration | P1 | Восстановление очереди | Остановить обработчик с захваченной операцией; дождаться истечения захвата; запустить | Работа продолжается; сохранённые ключ и расписание не потеряны |
| B-R018 | B-P07 | B-T018 | unit | P1 | Ограниченный повтор | Шесть ответов 503; часы управляемые, добавка 2 секунды | Первая попытка плюс пять повторов; задержки 7,17,32,62,122; далее проблема без автоматического повтора |
| B-R019 | B-P07 | B-T019 | unit | P1 | Опрос не расходует бюджет | Десять ответов 202; затем 503 | 202 опрашиваются через 10 секунд; бюджет ошибок не исчерпан |
| B-R020 | B-P08 | B-T020 | unit | P1 | Валидация результата | По отдельности: битый JSON, иной ID, status=other, duration=-1/1.5, data=%%% | Каждый ответ требует разбора; принятого результата нет |
| B-R021 | B-P08 | B-T021 | unit | P1 | Размер файла | Base64 от 102400 и 102401 байт | Первый допустим; второй требует разбора |
| B-R022 | B-P08 | B-T022 | integration | P1 | Дубликат | Применить один готовый ответ дважды | Один бизнес-эффект |
| B-R023 | B-P10 | B-T023 | integration | P1 | Старый ответ | Удержать результат операции A; создать B; доставить A | Результат B не заменён; история A сохранена |
| B-R024 | B-P09 | B-T024 | integration | P1 | Восстановление неизвестного ID | Потерять ответ POST; выполнить сверку | Повтор POST с прежним ключом восстанавливает ID; одно внешнее исполнение |
| B-R025 | B-P09 | B-T025 | acceptance | P1 | Утеря сохранённого результата | Для известного ID вернуть 404; выбрать воркера; переотправить | Ошибка видна; новая операция с новым ключом; старая история сохранена |
| B-R026 | B-P09 | B-T026 | integration | P1 | Автоматическая сверка | Внешний результат готов, локальный отсутствует; запустить сверку | Сохранены время и обе стороны; автоматического принятия нет |
| B-R027 | B-P11 | B-T027 | acceptance | P1 | Нагрузка | 5000 заданий; 20 клиентов; 30 секунд прогрев; 120 измерение; четыре равные группы | P95 чтения ≤2 с, изменения ≤3 с; ошибки учтены; общий лимит 2 CPU/4 ГиБ |

## Условия выполнения

Unit: доступные доменные/компонентные интерфейсы, управляемые часы и генератор случайности. Integration: согласованный контракт, собранные компоненты и изолированная PostgreSQL. Acceptance: общий запуск, демонстрационные пользователи, реальный эмулятор и команды сбоев.

Моки допускаются только на внешних границах изолированных тестов. Сквозное исполнение и счётчики проверяются на реальном эмуляторе. Прерывание процесса, перезапуск и сетевые сбои требуют управляемого тестового окружения.

## Дополнение по согласованным уточнениям

Источник: [CLARIFICATIONS.md](../CLARIFICATIONS.md). Условия выполнения — как у основной матрицы.

| Требование | Задача | Тест | Тип | Приоритет | Требование | Вход | Ожидаемый результат |
|---|---|---|---|---|---|---|---|
| B-R028 | B-P05 | B-T028 | unit | P1 | Имя после trim |   Ёж-1_А.txt   | Допустимо; сохранено Ёж-1_А.txt |
| B-R029 | B-P05 | B-T029 | unit | P1 | Запрещённые символы имени | По отдельности: пустая строка, три пробела, A B, A/B, A[B, A`B, 😀 | Отклонено; задание не создано |
| B-R030 | B-P07 | B-T030 | unit | P1 | Невалидный запрос без повторов | Внешний ответ 400; отдельно 422; продвинуть часы на 1000 секунд | Проблема оператору; новых автоматических попыток нет; операция сохранена |
| B-R031 | B-P09 | B-T031 | integration | P1 | Расхождение при сверке | Сохранён success с файлом A; сверка возвращает fail с пустой data для того же ID | Первый результат не перезаписан; второй сохранён в проблеме; оба наблюдения доступны |
| B-R032 | B-P09 | B-T032 | acceptance | P1 | Явное решение по сверке | После B-R031 повторная сверка подтверждает fail; оператор принимает | Принят подтверждённый fail; оба ответа и решение сохранены в истории |

## Исполняемые проверки

Ссылки на существующие тесты не меняют ожидаемое поведение матрицы. Если проверка охватывает лишь часть строки, оставшееся условие не считается подтверждённым.

| Тест / требование | Реализация |
|---|---|
| B-T001 / B-R001 | [BackendIT#migrationAndAssignmentIncludeReturnedButExcludeCompleted](../../backend/src/test/java/ru/tz/exchange/BackendIT.java) |
| B-T002 / B-R002 | [BackendIT#migrationAndAssignmentIncludeReturnedButExcludeCompleted](../../backend/src/test/java/ru/tz/exchange/BackendIT.java) |
| B-T003 / B-R003 | [BackendIT#httpCreationWithoutOperatorIsAssignedByCreateOperatorCommand](../../backend/src/test/java/ru/tz/exchange/BackendIT.java) |
| B-T004 / B-R004 | [BackendIT#httpCreationWithoutOperatorIsAssignedByCreateOperatorCommand](../../backend/src/test/java/ru/tz/exchange/BackendIT.java) |
| B-T005 / B-R005 | [BackendIT#migrationAndAssignmentIncludeReturnedButExcludeCompleted](../../backend/src/test/java/ru/tz/exchange/BackendIT.java) |
| B-T006 / B-R006 | [BackendIT#migrationAndAssignmentIncludeReturnedButExcludeCompleted](../../backend/src/test/java/ru/tz/exchange/BackendIT.java) |
| B-T007 / B-R007 | [BackendIT#isolationIncludesHistoryAndFile](../../backend/src/test/java/ru/tz/exchange/BackendIT.java) |
| B-T008 / B-R008 | [BackendIT#isolationIncludesHistoryAndFile](../../backend/src/test/java/ru/tz/exchange/BackendIT.java) |
| B-T009 / B-R009 | [BackendIT#isolationIncludesHistoryAndFile](../../backend/src/test/java/ru/tz/exchange/BackendIT.java) |
| B-T010 / B-R010 | [TaskTest#validatesBoundaries](../../backend/src/test/java/ru/tz/exchange/TaskTest.java) |
| B-T011 / B-R011 | [TaskTest#validatesBoundaries](../../backend/src/test/java/ru/tz/exchange/TaskTest.java) |
| B-T012 / B-R012 | [BackendIT#otherTypeIsRejectedByHttpDeserializationWithoutWrites](../../backend/src/test/java/ru/tz/exchange/BackendIT.java) |
| B-T013 / B-R013 | [TaskServiceTest#returnAndUnchangedEditPreserveOperator](../../backend/src/test/java/ru/tz/exchange/TaskServiceTest.java) |
| B-T014 / B-R014 | [BackendIT#externalMockIsOutsideTransactionAndFailIsFinalAfterAcceptance](../../backend/src/test/java/ru/tz/exchange/BackendIT.java) |
| B-T015 / B-R015 | [BackendIT#concurrentIdenticalCommandsCreateOneTaskAndReturnTheSameResponse](../../backend/src/test/java/ru/tz/exchange/BackendIT.java) |
| B-T016 / B-R016 | [BackendIT#commandAndAuditRollbackWithQueueFailure](../../backend/src/test/java/ru/tz/exchange/BackendIT.java) |
| B-T017 / B-R017 | [PersistenceIT#databaseRestartPreservesScheduleKeySnapshotAuditAndIdempotency](../../backend/src/test/java/ru/tz/exchange/PersistenceIT.java) |
| B-T018 / B-R018 | [ExchangeServiceTest#stopsAfterSixTemporaryFailuresAndPreservesKey](../../backend/src/test/java/ru/tz/exchange/ExchangeServiceTest.java) |
| B-T019 / B-R019 | [ExchangeServiceTest#processingDoesNotSpendErrorBudget](../../backend/src/test/java/ru/tz/exchange/ExchangeServiceTest.java) |
| B-T020 / B-R020 | [ResultTest#rejectsInvalidResponses](../../backend/src/test/java/ru/tz/exchange/ResultTest.java) |
| B-T021 / B-R021 | [ResultTest#checksDecodedSizeAndAllowsEmptyFail](../../backend/src/test/java/ru/tz/exchange/ResultTest.java) |
| B-T022 / B-R022 | [JointIT#heldRealResponseAndDuplicatePassTheActualHandlerWithValidReplayLeases](../../backend/src/test/java/ru/tz/exchange/JointIT.java) |
| B-T023 / B-R023 | [JointIT#heldRealResponseAndDuplicatePassTheActualHandlerWithValidReplayLeases](../../backend/src/test/java/ru/tz/exchange/JointIT.java) |
| B-T024 / B-R024 | [JointIT#lostPostAcknowledgmentIsRecoveredThroughRealRepeatedPostWithoutSecondExecution](../../backend/src/test/java/ru/tz/exchange/JointIT.java) |
| B-T025 / B-R025 | [JointIT#workerFailuresPreserveKeyAndLostResultCreatesNewOperation](../../backend/src/test/java/ru/tz/exchange/JointIT.java) |
| B-T026 / B-R026 | [BackendIT#reconciliationPreservesBothResultsUntilExplicitAcceptanceAndCompletion](../../backend/src/test/java/ru/tz/exchange/BackendIT.java) |
| B-T027 / B-R027 | [backend/load/profile.py](../../backend/load/profile.py) |
| B-T028 / B-R028 | [TaskTest#trimsNameAndAssignsWithoutSending](../../backend/src/test/java/ru/tz/exchange/TaskTest.java) |
| B-T029 / B-R029 | [TaskTest#rejectsInvalidName](../../backend/src/test/java/ru/tz/exchange/TaskTest.java) |
| B-T030 / B-R030 | [ExchangeServiceTest#permanentErrorsNeverRetry](../../backend/src/test/java/ru/tz/exchange/ExchangeServiceTest.java) |
| B-T031 / B-R031 | [BackendIT#reconciliationPreservesBothResultsUntilExplicitAcceptanceAndCompletion](../../backend/src/test/java/ru/tz/exchange/BackendIT.java) |
| B-T032 / B-R032 | [BackendIT#reconciliationPreservesBothResultsUntilExplicitAcceptanceAndCompletion](../../backend/src/test/java/ru/tz/exchange/BackendIT.java) |

## Команды запуска из корня проекта

```sh
mvn -f backend/pom.xml test
mvn -f backend/pom.xml verify
mvn -f emulator/pom.xml -DskipTests package
mvn -f backend/pom.xml -Pjoint verify
```

Обёртка `python3 testing/backend/tests/run.py` запускает быстрый набор без БД. Ошибка запуска или отсутствие компонента дают ненулевой код, не skip.

Дополнительная параметризация B-T028/B-R028/B-P05: [NameContractTest](../../backend/src/test/java/ru/tz/exchange/NameContractTest.java) проверяет каждый из 131 разрешённого символа отдельно.

## Дополнительные требования плана

| Требование | Задача | Тест | Тип | Приоритет | Условие | Ожидаемый результат | Реализация |
|---|---|---|---|---|---|---|---|
| B-R033 | B-P01 | B-T033 | unit | P1 | Архитектурные границы | Импорт доменом HTTP/ORM/Spring запрещён | [ArchitectureTest#dependenciesPointInward](../../backend/src/test/java/ru/tz/exchange/ArchitectureTest.java) |
| B-R034 | B-P02 | B-T034 | integration | P1 | Контракт сессии | Вход с demo-паролем; cookie меняется, выход инвалидирует сессию | [BackendIT#realSessionCookiePersistsAuthenticationRotatesAndLogsOut](../../backend/src/test/java/ru/tz/exchange/BackendIT.java) |
| B-R035 | B-P03 | B-T035 | integration | P1 | CSRF и аутентификация | Изменяющий запрос без CSRF или с чужими правами отклонён | [BackendIT#loginCsrfSessionAndAuthorization](../../backend/src/test/java/ru/tz/exchange/BackendIT.java) |
| B-R036 | B-P04 | B-T036 | integration | P1 | Конкурентное назначение | Параллельные создания сохраняют правило нагрузки внутри службы | [BackendIT#parallelAssignmentIsSerializedAndCommandsDoNotLoseChanges](../../backend/src/test/java/ru/tz/exchange/BackendIT.java) |
| B-R037 | B-P05 | B-T037 | unit | P1 | Конфликт версии | Изменение устаревшей версии отклонено, новой очереди нет | [TaskServiceTest#versionConflictPreventsMutationAndQueue](../../backend/src/test/java/ru/tz/exchange/TaskServiceTest.java) |
| B-R038 | B-P06 | B-T038 | integration | P1 | Граница транзакции | HTTP внешнего сервиса выполняется вне локальной транзакции | [BackendIT#externalMockIsOutsideTransactionAndFailIsFinalAfterAcceptance](../../backend/src/test/java/ru/tz/exchange/BackendIT.java) |
| B-R039 | B-P07 | B-T039 | unit | P1 | Таймаут HTTP | Соединение ограничено 3 с, запрос 5 с | [WorkerClientTest#createsPostAndEncodedGetWithFixedTimeout](../../backend/src/test/java/ru/tz/exchange/WorkerClientTest.java) |
| B-R040 | B-P07 | B-T040 | unit | P1 | Ручной повтор | Тот же ключ, новый ограниченный бюджет попыток | [TaskServiceTest#manualRetryPreservesKeyAndRestartsQueue](../../backend/src/test/java/ru/tz/exchange/TaskServiceTest.java) |
| B-R041 | B-P08 | B-T041 | unit | P1 | Истёкший захват | Ответ прежнего владельца захвата не меняет актуальное состояние | [ExchangeServiceTest#expiredLeaseCannotApplyAResponse](../../backend/src/test/java/ru/tz/exchange/ExchangeServiceTest.java) |
| B-R042 | B-P11 | B-T042 | unit | P1 | Служебная инициализация | Повтор bootstrap не перезаписывает существующие данные | [AdministrationTest#bootstrapRejectsInvalidEndpointsAndExistingState](../../backend/src/test/java/ru/tz/exchange/AdministrationTest.java) |

B-T020 дополнен [ResultContractTest](../../backend/src/test/java/ru/tz/exchange/ResultContractTest.java): processing_time=null,true,"12",[],{} и data=null,true,12,[],{} отклоняются как невалидный результат. Тип unit, приоритет P1; состояние БД не проверяется этим уровнем.

## QA-01: содержание дополнительных свидетельств, 16 ноября 2026

B-R043 / B-T043 / B-P02 — отсутствие секретов в техническом журнале с положительным контролем ошибок. `BackendIT#diagnosticsContainFailuresButNoSecretMarkers`: integration, полный CapturedOutput процесса на время теста; вход/CSRF реальные MockMvc, транспортная граница замокана. Реальный журнал эмулятора проверяется отдельно EmulatorIT. Это не совместный сетевой прогон.

Дополнительные методы зарегистрированы в traceability.json с `level`, `coverage`, `limitation`. Прямые атомарные доказательства отмечены direct, неполные связи — partial; уровень интеграции не объявляется приёмкой. Инвентаризированы unit проверки Task/Operation, TaskService, ExchangeService, RetryPolicy, WorkerClient; дополнительные BackendIT перечислены в реестре. Параметризация NameContractTest проверяет 131 одиночный допустимый символ, не сочетания; ResultContractTest проверяет по пять JSON-типов duration/data; ResultTest — девять невалидных ответов, отдельный метод проверяет чужой ID. ExchangeServiceTest permanentErrorsNeverRetry включает 400/422/409; matchingCompletedReconciliation проверяет RECONCILE и AUTO_RECONCILE.

Уточнения по содержанию связей:

- B-R003/004 теперь связаны с HTTP-созданием и прикладной командой создания оператора. CLI-процесс этой связью не доказан.
- B-R005/006 фактически integration (SQL в BackendIT), не unit; состояния выставляются фикстурой. Числа начальных нагрузок отличаются от примера, правило проверяется последовательностью назначений 10,20,10,10.
- B-R007/008/009 дополнены отрицательной HTTP-матрицей и сравнениями восьми бизнес-таблиц; `isolationIncludesHistoryAndFile` сам по себе — только прикладная граница.
- B-R012 HTTP other доказан отдельно; допустимые типы распределены по сценариям, единая параметризованная HTTP-проверка всех трёх типов отсутствует.
- B-R013 unit не проверяет историю/реальную отправку; браузерное свидетельство относится к frontend workflow.
- B-R014 BackendIT использует mock внешней системы: это integration, а не acceptance.
- B-R017 persistedLeaseRecovery использует время захвата, не перезапуск процесса; PersistenceIT даёт отдельное свидетельство перезапуска БД.
- B-R018/019 время виртуальное; реальное ожидание таймеров не измеряется.
- B-R020 unit подтверждает отказ десериализации и домена, не пользовательское отображение проблемы; B-R021 проверяет декодированный размер.
- B-R022/023/024 JointIT — реальные процессы и отдельные БД, без браузера; unit и BackendIT дополнительные связи не заменяют JointIT.
- B-R026 BackendIT явно проверяет ручную сверку; автоматическая связана с ExchangeServiceTest и freshWorkerRestoresUnknownDeliveryUsingPersistedKeyAndSnapshot, где mock граница и сохранённая очередь.
- B-R027 актуальные измерения фиксируются отдельным QA-07; старый JSON не является новым прогоном.
- B-R031/032 BackendIT проверяет подтверждение и сохранение истории на mock-ответах, браузерная приёмка относится к QA-04.
- B-R039 метод проверяет timeout запроса 5 секунд и кодирование URL; connect timeout 3 секунды не проверяется утверждением.
- B-R042 unit проверяет отказ повторной инициализации через mock, сохранность всей реальной БД этим методом не доказана.

Матрица HTTP: [HTTP_MATRIX.md](HTTP_MATRIX.md). Проверка метаданных проверяет существование связей, не их семантическую полноту.

B-R043 дополнен JointIT#completeProcessLogsRejectSecretsDuringRealFailures: реальные сетевые 503, потеря ответа и невалидный JSON; два новых контейнера PostgreSQL; оба полных журнала с положительными контролями и секретными маркерами.
