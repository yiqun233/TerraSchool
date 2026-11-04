-- REV-010: allow an enrollment that carries only a service fee and no course hours.
ALTER TABLE product.price_version
  DROP CONSTRAINT IF EXISTS price_version_price_check,
  DROP CONSTRAINT IF EXISTS price_version_hours_check,
  DROP CONSTRAINT IF EXISTS price_version_check;
ALTER TABLE product.price_version
  ADD CONSTRAINT price_version_service_only_shape_check CHECK (
    (price > 0 AND hours > 0 AND price >= service_fee)
    OR (price = 0 AND hours = 0 AND service_fee > 0)
  );

ALTER TABLE enrollment.enrollment
  DROP CONSTRAINT IF EXISTS enrollment_price_check,
  DROP CONSTRAINT IF EXISTS enrollment_hours_check,
  DROP CONSTRAINT IF EXISTS enrollment_check;
ALTER TABLE enrollment.enrollment
  ADD CONSTRAINT enrollment_service_only_shape_check CHECK (
    (price > 0 AND hours > 0 AND price >= service_fee)
    OR (price = 0 AND hours = 0 AND service_fee > 0)
  );
