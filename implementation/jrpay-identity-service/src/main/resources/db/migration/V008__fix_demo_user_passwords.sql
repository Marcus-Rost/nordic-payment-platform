-- V008: fix the demo-user bcrypt hashes seeded by V003.
--
-- The hash committed in V003 ($2a$10$qV3.HCZ/7l72KSlPjLktKuy9G3mNmoF/iXFkU8YWX1zh3lF5bKsny)
-- does NOT bcrypt-verify against the documented password 'demo-password-1!'
-- — caught during the 2026-04-27 GUI-to-GUI E2E run, where smoke tests and
-- the dev-token endpoint both rejected with "Bad credentials".
--
-- Until that run we papered over the issue with a runtime UPDATE; this
-- migration replaces the bad hash with a verified-correct one so a fresh
-- volume always has working demo users.
--
-- Hash generated via:
--     python3 -c "import bcrypt; print(bcrypt.hashpw(b'demo-password-1!',
--                                                    bcrypt.gensalt(rounds=10)).decode())"
-- and verified with bcrypt.checkpw before committing.

UPDATE identity_users
   SET password_hash = '$2b$10$R5OZdvfoI.JvU9INocQQ8eeuRhRFDbwhvfn/Bnz236h2VZKOwmemy'
 WHERE username IN ('admin@jrpay.example', 'ola.nordmann@example.com');
