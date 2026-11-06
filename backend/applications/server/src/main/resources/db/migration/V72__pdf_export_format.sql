ALTER TABLE infra.export_task DROP CONSTRAINT export_task_export_format_check;
ALTER TABLE infra.export_task
  ADD CONSTRAINT export_task_export_format_check
  CHECK (export_format IN ('CSV','XLSX','PDF'));
