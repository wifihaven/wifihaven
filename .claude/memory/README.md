## Shared agent memory

Memories that apply to anyone working on the codebase, regardless of which
host they're working from. Each file has frontmatter (`name`, `description`,
`type`) describing what it is and when it should kick in. Keep them short and
focused on durable rules — not transient state.

### What lives here vs. host-local memory

In-repo (this directory):
- Design invariants and code-level rules that everyone touching the codebase
  needs to know.
- Cross-cutting project policies (e.g. "wire schema versioning is deferred").

Host-local (under `~/.claude/projects/<slug>/memory/` on each machine):
- Live infrastructure details — SSH hosts, credentials, deployed paths.
- Hardware-specific facts (test iPad MACs on the physical test router).
- Workflow conventions for that machine (e.g. one host iterates on VM e2e,
  another does live deploys).

If a memory only makes sense on one host, leave it there. If it would change
the answer for any contributor on any host, move it here.

### Loading these on your machine

Claude Code's automatic memory loader reads from
`~/.claude/projects/<workspace-slug>/memory/`, not from the repo. To make
these files load alongside your host-local memories, symlink them in:

```bash
SLUG=$(pwd | sed 's:/:-:g')
TARGET=~/.claude/projects/$SLUG/memory
mkdir -p "$TARGET"
for f in .claude/memory/*.md; do
  base=$(basename "$f")
  [ "$base" = "README.md" ] && continue
  ln -sf "$PWD/$f" "$TARGET/$base"
done
```

Then add a line for each linked file to `$TARGET/MEMORY.md` (the index).
