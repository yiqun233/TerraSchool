ALTER TABLE teaching.class
  ADD COLUMN capacity integer,
  ADD CONSTRAINT class_capacity_range CHECK (capacity IS NULL OR capacity BETWEEN 0 AND 100000);
