-- 一条报名可以同时上多门课程（结算模式 2/3 的前提）：把"同时只能在一个班"的唯一约束放宽为
-- "同一班级不重复加入"，即唯一键加上 class_id。
DROP INDEX teaching.enrollment_current_class;
CREATE UNIQUE INDEX enrollment_current_class
  ON teaching.enrollment_class(enrollment_id,class_id) WHERE active;
