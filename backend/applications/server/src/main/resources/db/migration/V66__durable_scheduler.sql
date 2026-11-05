-- db-scheduler 16.12.0 persistence table. This is infrastructure metadata, not tenant business data.
-- The scheduler login is deliberately separate and has no RLS bypass or permissions on business schemas.
CREATE TABLE infra.scheduled_tasks (
  task_name text NOT NULL,
  task_instance text NOT NULL,
  task_data bytea,
  execution_time timestamptz NOT NULL,
  picked boolean NOT NULL,
  picked_by text,
  last_success timestamptz,
  last_failure timestamptz,
  consecutive_failures integer,
  last_heartbeat timestamptz,
  version bigint NOT NULL,
  priority smallint,
  PRIMARY KEY(task_name,task_instance)
);
CREATE INDEX scheduled_tasks_execution_time_idx ON infra.scheduled_tasks(execution_time);
CREATE INDEX scheduled_tasks_last_heartbeat_idx ON infra.scheduled_tasks(last_heartbeat);

GRANT USAGE ON SCHEMA infra TO ts_scheduler;
GRANT SELECT,INSERT,UPDATE,DELETE ON infra.scheduled_tasks TO ts_scheduler;
