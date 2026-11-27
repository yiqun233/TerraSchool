-- 产品可售校区：一个产品可以限定只在部分校区售卖；没有配置行时视为全校区可售。
CREATE TABLE product.product_campus (
  tenant_id uuid NOT NULL,
  product_id uuid NOT NULL,
  campus_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT product_campus_pk PRIMARY KEY (product_id, campus_id),
  CONSTRAINT product_campus_product_fk FOREIGN KEY (tenant_id, product_id)
    REFERENCES product.product(tenant_id, id),
  CONSTRAINT product_campus_campus_fk FOREIGN KEY (tenant_id, campus_id)
    REFERENCES organization.unit(tenant_id, id)
);

CREATE INDEX product_campus_campus ON product.product_campus(tenant_id, campus_id);

COMMENT ON TABLE product.product_campus IS '产品可售校区；无行表示该校所有校区均可售';

ALTER TABLE product.product_campus ENABLE ROW LEVEL SECURITY;
ALTER TABLE product.product_campus FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_scope ON product.product_campus
  USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE, DELETE ON product.product_campus TO ts_app;
