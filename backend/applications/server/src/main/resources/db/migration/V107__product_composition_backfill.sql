-- 回填产品课程组合：V106 的 INSERT ... SELECT 在迁移角色下受 RLS 限制看不到任何产品行，
-- 这里按租户逐一切换 app.tenant_id 后再回填，保证存量产品都有一条“单课程组成”。
DO $$
DECLARE
    t uuid;
BEGIN
    FOR t IN SELECT id FROM identity.tenant LOOP
        PERFORM set_config('app.tenant_id', t::text, true);
        INSERT INTO product.product_composition (tenant_id, product_id, curriculum_id, hours)
        SELECT p.tenant_id, p.id, p.curriculum_id,
               coalesce((SELECT pv.hours FROM product.price_version pv
                         WHERE pv.product_id = p.id AND pv.status = 'PUBLISHED'), 0)
        FROM product.product p
        WHERE p.tenant_id = t
        ON CONFLICT (product_id, curriculum_id) DO NOTHING;
    END LOOP;
    PERFORM set_config('app.tenant_id', '', true);
END $$;
