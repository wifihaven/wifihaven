# TEST-ONLY SSH keys

**DO NOT REUSE THESE KEYS FOR ANYTHING REAL.**

`client_test_ed25519` / `client_test_ed25519.pub` is a fixed keypair baked into
the client VM base image (`scripts/vm/build-client-base.sh`) so the orchestrator
can SSH into clients without per-boot key injection. The private half is
committed to the repository on purpose — these VMs are ephemeral test fixtures
on an isolated bridge with no inbound reachability from outside the host.

If you ever find yourself tempted to use this key for production, prod
preview, staging, or any host you do not personally own and intend to throw
away within the hour: stop, generate a fresh key, and walk away.
