# Tech stack decisions

This was originally in AGENTS.md §"Tech stack decisions"; see AGENTS.md for the TOC.

## Tech stack decisions

| Choice | Reason |
|--------|--------|
| Scala 3 + ZIO 2 | Type-safe effects, great for concurrent servers |
| ZIO HTTP | Native ZIO integration, good middleware support |
| Doobie | Typesafe SQL, no magic ORM |
| Flyway | Versioned DB migrations, easy to reason about schema |
| Lua (OpenWRT) | Native on OpenWRT, zero-dep enforcement agent |
| JWT (jwt-scala) | Stateless auth, easy to verify in DNS process too |
| Mill | Faster than sbt, simpler build files |
| React + Vite + TypeScript | Fast builds, good DX, type safety |
| Tailwind CSS | Utility-first, mobile-friendly without component library lock-in |
