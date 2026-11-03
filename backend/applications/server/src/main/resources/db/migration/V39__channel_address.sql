-- 可选地址沿用渠道版本；不定义联系人和分类。
ALTER TABLE admissions.channel ADD COLUMN address varchar(50) NOT NULL DEFAULT '';
