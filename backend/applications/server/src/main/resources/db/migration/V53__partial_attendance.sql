-- REV-011: record actual attendance minutes for partial absence.
ALTER TABLE teaching.attendance DROP CONSTRAINT attendance_status_check;
ALTER TABLE teaching.attendance
  ADD CONSTRAINT attendance_status_check CHECK(status IN ('PRESENT','ABSENT','LEAVE','PARTIAL'));
ALTER TABLE teaching.attendance ADD COLUMN attended_minutes integer;
ALTER TABLE teaching.attendance
  ADD CONSTRAINT attendance_minutes_check CHECK (
    (status = 'PARTIAL' AND attended_minutes > 0)
    OR (status <> 'PARTIAL' AND attended_minutes IS NULL)
  );
