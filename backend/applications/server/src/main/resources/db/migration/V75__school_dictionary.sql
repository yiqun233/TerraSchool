CREATE TABLE organization.dictionary_item (
  id uuid PRIMARY KEY DEFAULT uuidv7(),
  tenant_id uuid NOT NULL REFERENCES identity.tenant(id),
  school_id uuid NOT NULL,
  dictionary_code varchar(64) NOT NULL CHECK (dictionary_code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
  item_code varchar(64) NOT NULL CHECK (item_code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
  label varchar(100) NOT NULL CHECK (length(trim(label)) > 0),
  sort_order integer NOT NULL DEFAULT 0 CHECK (sort_order BETWEEN 0 AND 1000000),
  active boolean NOT NULL DEFAULT true,
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (tenant_id, school_id) REFERENCES organization.unit(tenant_id, id),
  UNIQUE (tenant_id, school_id, dictionary_code, item_code),
  UNIQUE (tenant_id, id)
);
CREATE INDEX dictionary_item_listing
  ON organization.dictionary_item(tenant_id, school_id, dictionary_code, active, sort_order, item_code);
ALTER TABLE organization.dictionary_item ENABLE ROW LEVEL SECURITY;
ALTER TABLE organization.dictionary_item FORCE ROW LEVEL SECURITY;
CREATE POLICY dictionary_item_tenant ON organization.dictionary_item
  USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE ON organization.dictionary_item TO ts_app;

CREATE TABLE organization.dictionary_item_request (
  tenant_id uuid NOT NULL REFERENCES identity.tenant(id),
  actor_id uuid NOT NULL,
  request_id uuid NOT NULL,
  request_hash varchar(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
  item_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, actor_id, request_id),
  FOREIGN KEY (tenant_id, actor_id) REFERENCES identity.account(tenant_id, id),
  FOREIGN KEY (tenant_id, item_id) REFERENCES organization.dictionary_item(tenant_id, id)
);
ALTER TABLE organization.dictionary_item_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE organization.dictionary_item_request FORCE ROW LEVEL SECURITY;
CREATE POLICY dictionary_item_request_tenant ON organization.dictionary_item_request
  USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
GRANT SELECT, INSERT ON organization.dictionary_item_request TO ts_app;
