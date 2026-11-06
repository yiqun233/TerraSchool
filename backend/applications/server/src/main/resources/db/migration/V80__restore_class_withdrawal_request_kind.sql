-- V77 已修正为包含 CLASS_WITHDRAWAL；本版保留为兼容与自检入口：
-- 1) 对已经执行过旧 V77 的开发库（checksum 需先 flyway repair）重建同一约束；
-- 2) 断言当前约束确实覆盖全部请求类型，避免以后再次漏项而只能等升级失败才发现。
ALTER TABLE teaching.creation_request DROP CONSTRAINT creation_request_kind_check;
ALTER TABLE teaching.creation_request
  ADD CONSTRAINT creation_request_kind_check
  CHECK (kind IN ('CLASS','LESSON','CLASS_TRANSFER','CLASS_WITHDRAWAL','CLASS_CAPACITY'));

-- 注意：同名约束在多个 schema 的 creation_request 上都有，断言必须按 schema+表限定。
DO $$
DECLARE
  definition text;
  expected text;
BEGIN
  SELECT pg_get_constraintdef(c.oid) INTO definition
  FROM pg_constraint c
  JOIN pg_class t ON t.oid = c.conrelid
  JOIN pg_namespace n ON n.oid = t.relnamespace
  WHERE c.conname = 'creation_request_kind_check'
    AND n.nspname = 'teaching' AND t.relname = 'creation_request';
  -- 用带引号的字面量匹配：'CLASS' 会命中 'CLASS_TRANSFER'/'CLASS_WITHDRAWAL'，
  -- 直接搜裸值会让“裸 CLASS 已被移除”这种缺陷漏检。
  FOREACH expected IN ARRAY ARRAY['CLASS','LESSON','CLASS_TRANSFER','CLASS_WITHDRAWAL','CLASS_CAPACITY']
  LOOP
    IF definition IS NULL OR position(quote_literal(expected) in definition) = 0 THEN
      RAISE EXCEPTION 'teaching.creation_request 请求类型约束缺少 %：%', expected, definition;
    END IF;
  END LOOP;
END $$;
