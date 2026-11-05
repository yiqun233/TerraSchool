ALTER TABLE infra.student_export_task
  ADD COLUMN export_type varchar(16) NOT NULL DEFAULT 'STUDENT'
    CHECK (export_type IN ('STUDENT','ENROLLMENT','RECEIPTS','LESSONS')),
  ADD COLUMN date_from varchar(10),
  ADD COLUMN date_to varchar(10);

ALTER TABLE infra.student_export_task
  RENAME TO export_task;

ALTER INDEX infra.student_export_task_pending_idx RENAME TO export_task_pending_idx;
ALTER INDEX infra.student_export_task_expiry_idx RENAME TO export_task_expiry_idx;
ALTER POLICY student_export_task_tenant ON infra.export_task RENAME TO export_task_tenant;
