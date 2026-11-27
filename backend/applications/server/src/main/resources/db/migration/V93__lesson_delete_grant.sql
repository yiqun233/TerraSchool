-- 删除草稿课次需要 DELETE 权限。V16 只授予了 SELECT/INSERT/UPDATE，
-- 运行时删除会以 “permission denied for table lesson” 失败。
GRANT DELETE ON teaching.lesson TO ts_app;
