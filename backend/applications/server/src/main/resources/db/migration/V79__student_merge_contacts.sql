-- REV-009: preserve contact links on the surviving primary archive and keep exact reversal data.
CREATE TABLE archive.student_merge_contact (
  tenant_id uuid NOT NULL,
  merge_id uuid NOT NULL,
  contact_id uuid NOT NULL,
  source_relation varchar(20) NOT NULL CHECK(source_relation IN ('FATHER','MOTHER','GUARDIAN','OTHER')),
  source_is_primary boolean NOT NULL,
  source_position integer NOT NULL,
  target_added boolean NOT NULL,
  target_is_primary boolean,
  target_position integer,
  PRIMARY KEY(tenant_id,merge_id,contact_id),
  FOREIGN KEY(tenant_id,merge_id) REFERENCES archive.student_merge(tenant_id,id),
  FOREIGN KEY(tenant_id,contact_id) REFERENCES archive.contact(tenant_id,id),
  CHECK((target_added AND target_is_primary IS NOT NULL AND target_position IS NOT NULL)
     OR (NOT target_added AND target_is_primary IS NULL AND target_position IS NULL))
);
ALTER TABLE archive.student_merge_contact ENABLE ROW LEVEL SECURITY;
ALTER TABLE archive.student_merge_contact FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON archive.student_merge_contact
  USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
  WITH CHECK(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
GRANT SELECT,INSERT ON archive.student_merge_contact TO ts_app;
