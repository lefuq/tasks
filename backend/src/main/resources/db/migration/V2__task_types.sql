create table task_types (
    code text primary key,
    name text not null
);
insert into task_types(code, name) values
    ('process', 'Обработка'),
    ('research', 'Исследование'),
    ('collect', 'Сбор');
alter table tasks add foreign key(type) references task_types(code);
update workers set name = replace(name, 'Воркер ', 'Исполнитель ') where name in ('Воркер 1', 'Воркер 2');
