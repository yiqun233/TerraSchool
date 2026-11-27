-- 一对一自动建班：产品标记与报名快照。
-- 产品标记 one_on_one 表示该产品是“一对一”售卖，审核通过后由报名侧编排自动建班。
ALTER TABLE product.product
    ADD COLUMN one_on_one boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN product.product.one_on_one IS '是否一对一产品（报名审核通过后自动建班）';

ALTER TABLE enrollment.enrollment
    ADD COLUMN one_on_one boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN enrollment.enrollment.one_on_one IS '报名时冻结的产品是否一对一；审核通过后自动建班用';

UPDATE enrollment.enrollment e
SET one_on_one = true
FROM product.product p
WHERE p.id = e.product_id AND p.one_on_one;
