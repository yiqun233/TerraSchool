-- 旧项目将团培定义为班级属性；报名、课时与资金仍分别归属于个人报名。
ALTER TABLE teaching.class
  ADD COLUMN group_training boolean NOT NULL DEFAULT false;
