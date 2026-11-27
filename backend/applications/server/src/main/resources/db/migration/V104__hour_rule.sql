-- 课时折算规则：课次时长（分钟）除以每分钟数得到课时数。
-- 放在学校级排课策略上，与最小间隔同级，避免再开一套参数表。
alter table teaching.schedule_policy
    add column minutes_per_hour int not null default 60
        check (minutes_per_hour between 15 and 240);

comment on column teaching.schedule_policy.minutes_per_hour is
    '每课时折算分钟数（15-240，默认 60）：课时数 = 课次时长 / 该值';
