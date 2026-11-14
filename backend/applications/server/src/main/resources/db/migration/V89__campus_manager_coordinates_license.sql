ALTER TABLE organization.unit
  ADD COLUMN manager_name varchar(100),
  ADD COLUMN longitude numeric,
  ADD COLUMN latitude numeric,
  ADD COLUMN school_license_number varchar(50),
  ADD CONSTRAINT unit_campus_details_only
    CHECK (kind = 'CAMPUS' OR (
      manager_name IS NULL AND longitude IS NULL AND latitude IS NULL AND school_license_number IS NULL
    )),
  ADD CONSTRAINT unit_campus_coordinates
    CHECK (
      (longitude IS NULL AND latitude IS NULL)
      OR (longitude IS NOT NULL AND latitude IS NOT NULL
        AND longitude BETWEEN -180 AND 180 AND latitude BETWEEN -90 AND 90
        AND scale(longitude) <= 8 AND scale(latitude) <= 8)
    );

GRANT UPDATE(manager_name,longitude,latitude,school_license_number) ON organization.unit TO ts_app;
