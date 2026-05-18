# Read-only Postgres role (`wifihaven_ro`) — operator guide

Migration V19 creates a `wifihaven_ro` role in the application database with
`SELECT` on all tables (current and future) and `USAGE` on schema `public`.
The migration creates the role without a password; the operator must set it
out-of-band before sharing the connection string.

This guide applies to both **staging** and **prod** (once #587 adds the prod
Render blueprint).

---

## 1. Set the role password via Render's PSQL shell

1. Open the [Render dashboard](https://dashboard.render.com/) and navigate to
   the database: **wifihaven-pg-staging** (or the prod equivalent once it
   exists).
2. Click **PSQL Command** — Render opens a browser-based psql session connected
   as the `owner` superuser of the managed database.
3. Run:

```sql
ALTER ROLE wifihaven_ro PASSWORD '<strong-random-password>';
```

   Generate the password locally with e.g.:
   ```sh
   openssl rand -base64 32
   ```

4. Close the PSQL shell.

---

## 2. Build the connection string

Render shows the external connection string for the database on the **Info**
tab of the database resource. It looks like:

```
postgresql://<user>:<password>@<host>:<port>/<database>?sslmode=require
```

Replace `<user>` with `wifihaven_ro` and `<password>` with the password you
set above.  The `<host>`, `<port>`, and `<database>` segments stay the same.

Example:
```
postgresql://wifihaven_ro:REDACTED@oregon-postgres.render.com:5432/wifihaven_staging?sslmode=require
```

---

## 3. Store in 1Password

Store the read-only connection string in the shared 1Password vault alongside
the admin API password.  Suggested item name:

```
WifiHaven — Staging RO Postgres URL
```

(or `WifiHaven — Prod RO Postgres URL` for the production instance).

---

## 4. Verify from a laptop

```sh
export STAGING_RO_URL="postgresql://wifihaven_ro:REDACTED@..."
psql "$STAGING_RO_URL" -c "SELECT count(*) FROM connection_events;"
```

Expected output: a single integer row.  If you see a permission error, check
that V19 ran (i.e. the Flyway migration was applied at deploy time).

---

## Notes

- The role has `SELECT` only — `INSERT`, `UPDATE`, `DELETE` will be denied.
- `ALTER DEFAULT PRIVILEGES` in V19 covers tables added by future migrations;
  no re-grant is needed.
- Never put the `wifihaven_ro` connection string in `render.yaml` or any
  committed file — it is an operator credential, not an app config variable.
