-- 初始化：建五个受限业务角色，并把库交给迁移角色。
-- 由 deploy/init-db.sh 以 postgres 超级用户执行，变量来自 compose 环境。
--
-- 这些角色与 verify.py、本地开发脚本口径一致：都不是超级用户、不能建库建角色、
-- 不能绕过 RLS。口令不落库、不落镜像，只由部署环境在启动时注入。
DO $$
DECLARE
    target text;
BEGIN
    FOREACH target IN ARRAY ARRAY['ts_app','ts_identity','ts_session','ts_audit','ts_scheduler','ts_platform','ts_migrator']
    LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = target) THEN
            EXECUTE format(
                'CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS', target);
        ELSE
            -- 已存在的角色也要收紧：不能因为历史上手工建过就保留超级用户或 RLS 绕过。
            EXECUTE format(
                'ALTER ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS', target);
        END IF;
    END LOOP;
END $$;
