alter table worker_settings drop constraint worker_settings_fault_check;

alter table worker_settings add constraint worker_settings_fault_check
    check (fault in ('normal', 'unavailable', 'lose_response', 'lost_result', 'invalid_json',
        'unknown_status', 'wrong_id', 'negative_time', 'fractional_time', 'invalid_base64', 'oversized_data'));
