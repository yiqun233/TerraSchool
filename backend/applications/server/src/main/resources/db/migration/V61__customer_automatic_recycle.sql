-- REV-009 initial automated pool return. Global task is disabled until school rules are confirmed.
ALTER TABLE admissions.customer ADD COLUMN owner_assigned_at timestamptz;
UPDATE admissions.customer SET owner_assigned_at=created_at WHERE owner_assigned_at IS NULL;
ALTER TABLE admissions.customer ALTER COLUMN owner_assigned_at SET DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE admissions.customer ALTER COLUMN owner_assigned_at SET NOT NULL;
GRANT UPDATE(owner_id,in_pool,version,owner_assigned_at) ON admissions.customer TO ts_app;
CREATE INDEX customer_owner_assignment_age
  ON admissions.customer(tenant_id,school_id,owner_assigned_at,id)
  WHERE owner_id IS NOT NULL AND in_pool=false AND merged_into_id IS NULL;

ALTER TABLE audit.customer_event ALTER COLUMN actor_id DROP NOT NULL;
ALTER TABLE audit.customer_event ADD COLUMN actor_type varchar(12) NOT NULL DEFAULT 'USER'
  CHECK(actor_type IN ('USER','SYSTEM'));
ALTER TABLE audit.customer_event ADD COLUMN detail varchar(80);
ALTER TABLE audit.customer_event DROP CONSTRAINT customer_event_action_check;
ALTER TABLE audit.customer_event ADD CHECK(action IN (
  'CREATED','PHONE_VIEWED','DUPLICATE_DETECTED','CANCELLED','FOLLOWUP_ADDED','FOLLOWUP_VIEWED',
  'POOL_RELEASED','POOL_CLAIMED','MERGED','MERGE_REVERSED','STUDENT_LINKED','OWNER_ASSIGNED',
  'AUTO_RECYCLED'));
ALTER TABLE audit.customer_event DROP CONSTRAINT customer_event_check;
ALTER TABLE audit.customer_event ADD CHECK (
  (action IN ('CREATED','PHONE_VIEWED','FOLLOWUP_ADDED','FOLLOWUP_VIEWED','POOL_RELEASED',
    'POOL_CLAIMED','MERGED','MERGE_REVERSED','STUDENT_LINKED','OWNER_ASSIGNED','AUTO_RECYCLED')
    AND customer_id IS NOT NULL)
  OR (action IN ('DUPLICATE_DETECTED','CANCELLED') AND request_id IS NOT NULL));
ALTER TABLE audit.customer_event ADD CHECK(
  (actor_type='USER' AND actor_id IS NOT NULL) OR (actor_type='SYSTEM' AND actor_id IS NULL));
GRANT UPDATE(owner_id,in_pool,version) ON admissions.customer TO ts_app;
