-- 优惠与报名创建共用主体/请求号账本，动作进入摘要，跨动作复用请求号拒绝。
ALTER TABLE enrollment.creation_request DROP CONSTRAINT creation_request_kind_check;
ALTER TABLE enrollment.creation_request ADD CHECK(kind IN ('ENROLLMENT','DISCOUNT'));
