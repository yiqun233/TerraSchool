CREATE SCHEMA identity;
CREATE SCHEMA organization;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
CREATE TABLE identity.tenant(id uuid PRIMARY KEY DEFAULT uuidv7(),name varchar(100) NOT NULL,active boolean NOT NULL DEFAULT true);
CREATE TABLE identity.account(id uuid PRIMARY KEY DEFAULT uuidv7(),tenant_id uuid NOT NULL REFERENCES identity.tenant(id),login varchar(150) NOT NULL UNIQUE,password_hash varchar(255) NOT NULL,active boolean NOT NULL DEFAULT true,organization_read boolean NOT NULL DEFAULT false,UNIQUE(tenant_id,id));
CREATE TABLE organization.unit(
 id uuid PRIMARY KEY DEFAULT uuidv7(),tenant_id uuid NOT NULL REFERENCES identity.tenant(id),school_id uuid NOT NULL,
 kind varchar(20) NOT NULL CHECK(kind IN ('SCHOOL','CAMPUS','DEPARTMENT')),name varchar(100) NOT NULL CHECK(length(trim(name))>0),version bigint NOT NULL DEFAULT 0,
 school_kind varchar(20) NOT NULL DEFAULT 'SCHOOL' CHECK(school_kind='SCHOOL'),
 CHECK(kind<>'SCHOOL' OR id=school_id),UNIQUE(tenant_id,id),UNIQUE(tenant_id,id,kind),
 FOREIGN KEY(tenant_id,school_id,school_kind) REFERENCES organization.unit(tenant_id,id,kind)
);
CREATE INDEX unit_school_page ON organization.unit(tenant_id,school_id,id);
CREATE TABLE identity.school_scope(account_id uuid NOT NULL,tenant_id uuid NOT NULL,school_id uuid NOT NULL,school_kind varchar(20) NOT NULL DEFAULT 'SCHOOL' CHECK(school_kind='SCHOOL'),PRIMARY KEY(account_id,school_id),FOREIGN KEY(tenant_id,account_id) REFERENCES identity.account(tenant_id,id),FOREIGN KEY(tenant_id,school_id,school_kind) REFERENCES organization.unit(tenant_id,id,kind));
ALTER TABLE organization.unit ENABLE ROW LEVEL SECURITY;
ALTER TABLE organization.unit FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON organization.unit USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid) WITH CHECK(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
GRANT USAGE ON SCHEMA organization TO ts_app;
GRANT SELECT ON organization.unit TO ts_app;
GRANT USAGE ON SCHEMA identity TO ts_identity;
GRANT SELECT ON ALL TABLES IN SCHEMA identity TO ts_identity;
