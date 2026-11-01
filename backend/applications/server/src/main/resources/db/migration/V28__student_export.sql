-- 学员导出：导出是独立动作，要能审计"谁导出了哪个学校的多少行"。
ALTER TABLE audit.student_event DROP CONSTRAINT student_event_action_check;
ALTER TABLE audit.student_event
  ADD CHECK(action IN ('STUDENT_CREATED','STUDENT_UPDATED','STUDENT_SENSITIVE_READ','STUDENT_EXPORTED'));
