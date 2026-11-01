ALTER TABLE identity.role_action DROP CONSTRAINT role_action_action_check;
ALTER TABLE identity.role_action ADD CHECK(action IN ('ORGANIZATION_READ','ORGANIZATION_MAINTAIN','AUTHORIZATION_MANAGE','EMPLOYEE_READ','EMPLOYEE_MAINTAIN','TEACHER_READ','TEACHER_MAINTAIN','ROOM_READ','ROOM_MAINTAIN'));
ALTER TABLE identity.account_permission DROP CONSTRAINT account_permission_action_check;
ALTER TABLE identity.account_permission ADD CHECK(action IN ('ORGANIZATION_READ','ORGANIZATION_MAINTAIN','AUTHORIZATION_MANAGE','EMPLOYEE_READ','EMPLOYEE_MAINTAIN','TEACHER_READ','TEACHER_MAINTAIN','ROOM_READ','ROOM_MAINTAIN'));
ALTER TABLE organization.unit ADD UNIQUE(tenant_id,id,school_id,kind);
CREATE TABLE organization.employee (
 id uuid PRIMARY KEY DEFAULT uuidv7(),tenant_id uuid NOT NULL,school_id uuid NOT NULL,
 school_kind varchar(20) NOT NULL DEFAULT 'SCHOOL' CHECK(school_kind='SCHOOL'),
 campus_id uuid,campus_kind varchar(20) NOT NULL DEFAULT 'CAMPUS' CHECK(campus_kind='CAMPUS'),
 department_id uuid,department_kind varchar(20) NOT NULL DEFAULT 'DEPARTMENT' CHECK(department_kind='DEPARTMENT'),
 name varchar(100) NOT NULL CHECK(length(trim(name))>0),active boolean NOT NULL DEFAULT true,version bigint NOT NULL DEFAULT 0,
 UNIQUE(tenant_id,school_id,id),
 FOREIGN KEY(tenant_id,school_id,school_kind) REFERENCES organization.unit(tenant_id,id,kind),
 FOREIGN KEY(tenant_id,campus_id,school_id,campus_kind) REFERENCES organization.unit(tenant_id,id,school_id,kind),
 FOREIGN KEY(tenant_id,department_id,school_id,department_kind) REFERENCES organization.unit(tenant_id,id,school_id,kind)
);
CREATE TABLE organization.teacher (
 id uuid PRIMARY KEY DEFAULT uuidv7(),tenant_id uuid NOT NULL,school_id uuid NOT NULL,
 school_kind varchar(20) NOT NULL DEFAULT 'SCHOOL' CHECK(school_kind='SCHOOL'),employee_id uuid,
 name varchar(100) NOT NULL CHECK(length(trim(name))>0),active boolean NOT NULL DEFAULT true,version bigint NOT NULL DEFAULT 0,
 UNIQUE(tenant_id,school_id,employee_id),
 FOREIGN KEY(tenant_id,school_id,school_kind) REFERENCES organization.unit(tenant_id,id,kind),
 FOREIGN KEY(tenant_id,school_id,employee_id) REFERENCES organization.employee(tenant_id,school_id,id)
);
CREATE TABLE organization.room (
 id uuid PRIMARY KEY DEFAULT uuidv7(),tenant_id uuid NOT NULL,school_id uuid NOT NULL,
 school_kind varchar(20) NOT NULL DEFAULT 'SCHOOL' CHECK(school_kind='SCHOOL'),
 campus_id uuid,campus_kind varchar(20) NOT NULL DEFAULT 'CAMPUS' CHECK(campus_kind='CAMPUS'),
 name varchar(100) NOT NULL CHECK(length(trim(name))>0),capacity integer CHECK(capacity>=0 AND capacity<=100000),
 active boolean NOT NULL DEFAULT true,version bigint NOT NULL DEFAULT 0,
 FOREIGN KEY(tenant_id,school_id,school_kind) REFERENCES organization.unit(tenant_id,id,kind),
 FOREIGN KEY(tenant_id,campus_id,school_id,campus_kind) REFERENCES organization.unit(tenant_id,id,school_id,kind)
);
CREATE TABLE organization.resource_creation_request (
 tenant_id uuid NOT NULL,actor_id uuid NOT NULL,request_id uuid NOT NULL,school_id uuid NOT NULL,
 kind varchar(20) NOT NULL CHECK(kind IN ('EMPLOYEE','TEACHER','ROOM')),resource_id uuid NOT NULL,
 input_json jsonb NOT NULL,created_json jsonb NOT NULL,PRIMARY KEY(tenant_id,actor_id,request_id)
);
CREATE TABLE organization.school_creation_request (
 tenant_id uuid NOT NULL,actor_id uuid NOT NULL,request_id uuid NOT NULL,school_id uuid NOT NULL,
 name varchar(100) NOT NULL,PRIMARY KEY(tenant_id,actor_id,request_id),
 FOREIGN KEY(tenant_id,school_id) REFERENCES organization.unit(tenant_id,id)
);
DO $policies$
DECLARE tab text;
BEGIN
 FOR tab IN SELECT unnest(ARRAY['employee','teacher','room','resource_creation_request','school_creation_request']) LOOP
  EXECUTE format('ALTER TABLE organization.%I ENABLE ROW LEVEL SECURITY',tab);
  EXECUTE format('ALTER TABLE organization.%I FORCE ROW LEVEL SECURITY',tab);
  EXECUTE format('CREATE POLICY tenant_scope ON organization.%I USING(tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK(tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid)',tab);
  EXECUTE format('GRANT SELECT,INSERT ON organization.%I TO ts_app',tab);
 END LOOP;
END;
$policies$;
GRANT UPDATE(name,campus_id,department_id,active,version) ON organization.employee TO ts_app;
GRANT UPDATE(name,employee_id,active,version) ON organization.teacher TO ts_app;
GRANT UPDATE(name,campus_id,capacity,active,version) ON organization.room TO ts_app;
CREATE INDEX employee_school_page ON organization.employee(tenant_id,school_id,id);
CREATE INDEX teacher_school_page ON organization.teacher(tenant_id,school_id,id);
CREATE INDEX room_school_page ON organization.room(tenant_id,school_id,id);
CREATE TABLE audit.resource_event (
 id uuid PRIMARY KEY DEFAULT uuidv7(),tenant_id uuid NOT NULL,actor_id uuid NOT NULL,
 school_id uuid NOT NULL,resource_id uuid NOT NULL,kind varchar(20) NOT NULL,
 action varchar(20) NOT NULL,before_json jsonb,after_json jsonb NOT NULL,trace_id uuid NOT NULL,
 happened_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE audit.resource_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit.resource_event FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON audit.resource_event
 USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
GRANT INSERT ON audit.resource_event TO ts_app;
ALTER TABLE audit.operation_failure DROP CONSTRAINT operation_failure_action_check;
ALTER TABLE audit.operation_failure ADD CHECK(action IN ('ORGANIZATION_CREATE','ORGANIZATION_RENAME','AUTHORIZATION_REPLACE','ROLE_TEMPLATE_CREATE','ROLE_TEMPLATE_REPLACE','ACCOUNT_CREATE','ACCOUNT_STATUS','ACCOUNT_PASSWORD_RESET','ACCOUNT_SESSIONS_REVOKE','ORGANIZATION_SCHOOL_CREATE','EMPLOYEE_CREATE','EMPLOYEE_UPDATE','TEACHER_CREATE','TEACHER_UPDATE','ROOM_CREATE','ROOM_UPDATE'));
