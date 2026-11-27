-- 产品包（课程组合）：一个产品可以由多门课程组成，价格版本的价格即打包价（相当于折扣后的总价）。
-- product.product.curriculum_id 继续保留为“主课程”，用于班级默认课程、可售过滤与兼容旧逻辑。
-- 语句均为可重入：首次执行中途失败后，允许原样重跑。

CREATE TABLE IF NOT EXISTS product.product_composition (
    tenant_id    uuid NOT NULL,
    product_id   uuid NOT NULL,
    curriculum_id uuid NOT NULL,
    hours        numeric(10,2) NOT NULL DEFAULT 0 CHECK (hours >= 0),
    created_at   timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (product_id, curriculum_id),
    FOREIGN KEY (tenant_id, product_id) REFERENCES product.product(tenant_id, id),
    FOREIGN KEY (tenant_id, curriculum_id) REFERENCES product.curriculum(tenant_id, id)
);

COMMENT ON TABLE product.product_composition IS '产品包含的课程与各自课时；单课程产品也有一行，主课程为第一行';
COMMENT ON COLUMN product.product_composition.hours IS '该课程在产品总课时中的分配；报名时课程明细按它预填';

ALTER TABLE product.product_composition ENABLE ROW LEVEL SECURITY;
ALTER TABLE product.product_composition FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_scope ON product.product_composition;
CREATE POLICY tenant_scope ON product.product_composition
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON product.product_composition TO ts_app;

-- 存量产品回填为“单课程组成”，课时取当前已发布价格版本的课时（没有则为 0）。
INSERT INTO product.product_composition (tenant_id, product_id, curriculum_id, hours)
SELECT p.tenant_id, p.id, p.curriculum_id,
       coalesce((SELECT pv.hours FROM product.price_version pv
                 WHERE pv.product_id = p.id AND pv.status = 'PUBLISHED'), 0)
FROM product.product p
ON CONFLICT (product_id, curriculum_id) DO NOTHING;

ALTER TABLE audit.product_event DROP CONSTRAINT IF EXISTS product_event_action_check;
ALTER TABLE audit.product_event ADD CHECK(action IN (
 'CURRICULUM_CREATED','CURRICULUM_UPDATED','PRODUCT_CREATED','PRODUCT_UPDATED',
 'PRICE_VERSION_DRAFTED','PRICE_VERSION_UPDATED','PRICE_VERSION_PUBLISHED','PRICE_VERSION_RETIRED',
 'PRODUCT_GROUP_CREATED','PRODUCT_GROUP_UPDATED','PRODUCT_GROUP_ASSIGNED','PRODUCT_CAMPUS_SCOPE',
 'PRODUCT_CURRICULA_UPDATED'));

