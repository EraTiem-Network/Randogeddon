-- Whether the single sign-on provider calls the account's e-mail address verified (Phase 16): null for an address
-- that isn't the provider's (or one it only told us about, without saying). Shown to Administrators.
ALTER TABLE users ADD COLUMN email_verified_by_provider boolean;
