-- V77 增加容量调整时遗漏了 V58 已支持的退班请求；追加修复，不重写已执行迁移。
ALTER TABLE teaching.creation_request DROP CONSTRAINT creation_request_kind_check;
ALTER TABLE teaching.creation_request
  ADD CONSTRAINT creation_request_kind_check
  CHECK (kind IN ('CLASS','LESSON','CLASS_TRANSFER','CLASS_WITHDRAWAL','CLASS_CAPACITY'));
