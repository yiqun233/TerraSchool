ALTER TABLE organization.unit
  ADD COLUMN short_name varchar(20),
  ADD COLUMN address varchar(200),
  ADD COLUMN postal_code varchar(50),
  ADD COLUMN bus_routes varchar(300);

ALTER TABLE organization.unit
  ADD CONSTRAINT unit_campus_profile_only
    CHECK (kind = 'CAMPUS' OR (short_name IS NULL AND address IS NULL AND postal_code IS NULL AND bus_routes IS NULL));

GRANT UPDATE(short_name,address,postal_code,bus_routes,version) ON organization.unit TO ts_app;

ALTER TABLE audit.business_event
  ADD COLUMN detail_before text,
  ADD COLUMN detail_after text;
