ALTER TABLE infra.export_task
  ADD COLUMN export_format varchar(8) NOT NULL DEFAULT 'CSV'
    CHECK (export_format IN ('CSV','XLSX'));
