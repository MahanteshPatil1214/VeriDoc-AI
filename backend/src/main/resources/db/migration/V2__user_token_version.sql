-- Refresh-token revocation epoch.
--
-- Every user starts at 0. Issued JWTs carry this value in the `ver` claim.
-- A password change increments it, so refresh tokens minted before the change
-- no longer match the account and cannot be exchanged. Access tokens are not
-- checked against this column; they remain valid until their normal short TTL.
ALTER TABLE users
    ADD COLUMN token_version BIGINT NOT NULL DEFAULT 0;
