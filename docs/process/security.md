# Security notes

This was originally in AGENTS.md §"Security notes"; see AGENTS.md for the TOC.

## Security notes

- JWT secret must be at least 32 chars, set in config
- Router tokens are single-use enrollment tokens; after enrollment a separate bearer token is issued
- Passwords are bcrypt hashed (cost factor 12)
- Admin vs ReadOnly enforced via JWT claims + middleware
- SQL injection impossible via Doobie parameterized queries
- Config file contains DB credentials — never commit it (in .gitignore)
