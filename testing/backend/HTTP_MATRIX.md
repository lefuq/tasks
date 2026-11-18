# Отрицательная HTTP-матрица

Исполняется `BackendIT.negativeHttpMatrixPreservesEveryBusinessTable` и `allProtectedHttpEndpointsRejectAnonymousAndMutationsRejectMissingCsrf`. 403 соответствует Error403 контракта. Автор — user1, другой инициатор службы — user2, инициатор другой службы — user3, назначенный оператор — user10, другой оператор службы — user20, оператор другой службы — user30.

| Endpoint | Допуск / отказ |
|---|---|
| GET /api/me | anonymous 401; действительная сессия проверена realSessionCookiePersistsAuthenticationRotatesAndLogsOut |
| POST /api/session | неверный пароль 401, без CSRF 403; успешный вход loginCsrfSessionAndAuthorization |
| DELETE /api/session | без CSRF 403; действительная сессия + CSRF 204 и последующий 401 |
| GET /api/workers | anonymous 401; автор 200 без endpoint |
| GET /api/tasks | anonymous 401; чужие инициаторы/служба получают пустой список |
| POST /api/tasks | операторы обеих служб 403; инициатор 201 |
| GET /api/tasks/{id}, /history, /result/file | автор и оба оператора своей службы 200; другой автор и другая служба 403 |
| PATCH /api/tasks/{id} | другой автор, все операторы и другая служба 403 |
| PUT /api/tasks/{id}/worker | все инициаторы, неназначенный и чужой оператор 403 |
| POST /api/tasks/{id}/send,retry,return,accept,reconcile | все инициаторы, неназначенный и чужой оператор 403 |
| GET /api/problems | anonymous 401; problemListFiltersAuthorsAndServicesButIncludesUnassignedOperators: автор и оба оператора своей службы видят одно проблемное задание, чужие инициаторы и служба — пустой список |

Все защищённые команды дополнительно проверяются без аутентификации с CSRF (401), для пяти пользователей без CSRF (403). Каждый отказ матрицы сравнивает все строки tasks, operations, exchange_jobs, exchange_attempts, problems, reconciliations, audit, api_requests. История и файл проверяются при существующем результате, чтобы отсутствие файла не маскировало нарушение доступа; отказ возвращает только Forbidden без заголовка скачивания.

Матрица проверяет права при корректной версии. Она не утверждает порядок ошибок одновременно неверной версии и отсутствия прав. Допустимые действия зависят от состояния и проверяются отдельными сценариями BackendIT/TaskTest.
