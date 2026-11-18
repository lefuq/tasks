create table worker_settings (
    worker text primary key,
    fault text not null check (fault in ('normal', 'unavailable', 'lose_response', 'invalid_json', 'unknown_status', 'wrong_id', 'negative_time', 'fractional_time', 'invalid_base64', 'oversized_data')),
    outcome text not null check (outcome in ('random', 'success', 'fail'))
);

create table operations (
    id uuid primary key,
    idempotency_key text not null unique,
    name varchar(100) not null,
    description varchar(1000) not null,
    type text not null check (type in ('process', 'research', 'collect')),
    worker text not null,
    started_at timestamptz not null,
    ready_at timestamptz not null,
    finished_at timestamptz,
    outcome text not null check (outcome in ('success', 'fail')),
    execution_count integer not null check (execution_count = 1),
    data text,
    check (ready_at >= started_at + interval '10 seconds' and ready_at <= started_at + interval '60 seconds'),
    check ((finished_at is null and data is null) or
        (finished_at >= ready_at and data is not null and
            ((outcome = 'fail' and data = '') or
             (outcome = 'success' and octet_length(decode(data, 'base64')) <= 102400))))
);

create index operations_pending on operations(ready_at) where finished_at is null;
