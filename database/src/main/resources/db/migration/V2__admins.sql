-- Admins see and reach the admin panel (Phase 3); granted with `randogeddon admins --grant <person>`
ALTER TABLE users ADD COLUMN is_admin boolean NOT NULL DEFAULT false;
