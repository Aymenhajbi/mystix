-- Tenant root: every business table will reference company(id) through company_id.
CREATE TABLE company (
    id             UUID         PRIMARY KEY,
    ice            CHAR(15)     NOT NULL,
    legal_name     VARCHAR(255) NOT NULL,
    tax_identifier VARCHAR(20),
    created_at     TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_company_ice UNIQUE (ice),
    CONSTRAINT ck_company_ice_digits CHECK (ice ~ '^[0-9]{15}$'),
    CONSTRAINT ck_company_legal_name_not_blank CHECK (length(trim(legal_name)) > 0)
);

COMMENT ON COLUMN company.ice IS 'Identifiant Commun de l''Entreprise, 15 digits';
COMMENT ON COLUMN company.tax_identifier IS 'Identifiant Fiscal (IF)';
