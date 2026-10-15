-- ArtifactStore (ADR-0017): an artefact is either in this table (database mode) or in the S3-compatible object store
-- (s3 mode), never both and never neither. The SHA-256 stays here in both cases, to check what is read back.
ALTER TABLE invoice_artifact ALTER COLUMN content DROP NOT NULL;
ALTER TABLE invoice_artifact ADD COLUMN storage_key VARCHAR(512);
ALTER TABLE invoice_artifact ADD COLUMN storage_version VARCHAR(255);
ALTER TABLE invoice_artifact ADD COLUMN size_bytes BIGINT;
UPDATE invoice_artifact SET size_bytes = octet_length(content);
ALTER TABLE invoice_artifact ALTER COLUMN size_bytes SET NOT NULL;
ALTER TABLE invoice_artifact ADD CONSTRAINT ck_invoice_artifact_location
    CHECK ((content IS NOT NULL) <> (storage_key IS NOT NULL));
