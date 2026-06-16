package wifihaven.api.routes

import wifihaven.api.auth.*
import wifihaven.api.db.*
import wifihaven.shared.*
import wifihaven.shared.types.*
import zio.*
import zio.http.*
import zio.json.*

// #1320 / #1308: admin-facing CRUD + audit surface for the household-global
// policy that PolicyService (#1318) collapses into `PolicySnapshot.global`.
//
// `global.extraAllowed` is a SECURITY-SENSITIVE bypass surface — a host here is
// reachable from EVERY device regardless of any block (it is the top of the
// precedence ladder, design §5.2). So reads expose the full audit trail
// (`reason` / `added_by` / `removed_by`, resolved to usernames) and writes
// require admin. The router never sees any of this — only the flat hostname /
// category lists assembled by the GlobalPolicyRepo (design §7).
//
// These routes are covered by the HTTP middleware's route-templated duration /
// status series, so per the "new functionality ships with metrics" rule they
// need no bespoke metric of their own.
//
// #1570: handlers fail with a typed [[ApiError]] mapped centrally by
// [[ErrorMapper.errorToResponse]]; the [[wifihaven.api.ErrorBoundary]] logs (4xx WARN /
// 5xx ERROR) + meters each error. Every case reproduces the EXACT status + body the
// hand-rolled `Response.badRequest`/`ErrorMapper.dbErrorToResponse` produced before, so the
// SPA sees identical responses. Auth helpers still return `Response` and are bridged via
// [[ApiError.Wrapped]] (their migration is Routes.scala's, a later stage).
object GlobalPolicyRoutes {
  def routes(
      auth: AuthService,
      repo: GlobalPolicyRepo,
      userRepo: UserRepo,
  ): Routes[Any, Response] = {

    // Resolve the acting admin's username (JWT `sub`) to the user id stored in
    // the `added_by` / `removed_by` audit columns. A missing row (e.g. token
    // for a since-deleted user) records `None` rather than failing the write.
    def actingUserId(claims: JwtClaims): IO[ApiError, Option[Long]] =
      userRepo
        .findByUsername(claims.sub)
        .mapError(ApiError.Db(_))
        .map(_.map(_.id.value))

    def view: IO[ApiError, GlobalPolicyView] =
      for {
        rules  <- repo.get.mapError(ApiError.Db(_))
        allow  <- repo.allowAudit.mapError(ApiError.Db(_))
        blocks <- repo.blockAudit.mapError(ApiError.Db(_))
      } yield GlobalPolicyView(
        allow = allow,
        blocks = blocks,
        blocklistIds = rules.blocklistIds,
        blocked = rules.blocked,
        blockReason = rules.blockReason,
        blockIpOnly = rules.blockIpOnly,
      )

    def parseBody[A: JsonDecoder](req: Request): IO[ApiError, A] =
      for {
        body <- req.body.asString.orElseFail(ApiError.BadRequest(""))
        a    <- ZIO.fromEither(body.fromJson[A]).mapError(ApiError.DecodeFailure(_))
      } yield a

    Routes(
      // Full management view: active + soft-deleted history for the allow/block
      // sets plus the flat global flags. Read access only — any authenticated
      // user may view, mirroring the household-settings GET.
      Method.GET / "api" / "global" / "policy" ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            _ <- requireAuth(req, auth).mapError(ApiError.Wrapped(_))
            v <- view
          } yield Response.json(v.toJson)
          handle.mapError(ErrorMapper.errorToResponse)
        },

      // ── always-reachable allow set ────────────────────────────────────────
      Method.POST / "api" / "global" / "allow"                    ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            claims <- requireAdmin(req, auth).mapError(ApiError.Wrapped(_))
            r      <- parseBody[AddGlobalHostRequest](req)
            uid    <- actingUserId(claims)
            _      <- repo.addAllow(r.host, r.reason, uid).mapError(ApiError.Db(_))
          } yield Response.ok
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.DELETE / "api" / "global" / "allow" / string("host") ->
        handler { (host: String, req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            claims <- requireAdmin(req, auth).mapError(ApiError.Wrapped(_))
            uid    <- actingUserId(claims)
            _      <- repo
              .removeAllow(Hostname.unsafe(host), uid)
              .mapError(ApiError.Db(_))
          } yield Response.ok
          handle.mapError(ErrorMapper.errorToResponse)
        },

      // ── network-wide block set ────────────────────────────────────────────
      Method.POST / "api" / "global" / "blocks"                    ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            claims <- requireAdmin(req, auth).mapError(ApiError.Wrapped(_))
            r      <- parseBody[AddGlobalHostRequest](req)
            uid    <- actingUserId(claims)
            _      <- repo.addBlock(r.host, r.reason, uid).mapError(ApiError.Db(_))
          } yield Response.ok
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.DELETE / "api" / "global" / "blocks" / string("host") ->
        handler { (host: String, req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            claims <- requireAdmin(req, auth).mapError(ApiError.Wrapped(_))
            uid    <- actingUserId(claims)
            _      <- repo
              .removeBlock(Hostname.unsafe(host), uid)
              .mapError(ApiError.Db(_))
          } yield Response.ok
          handle.mapError(ErrorMapper.errorToResponse)
        },

      // ── household-global category set (replace wholesale) ─────────────────
      // #1456: wholesale PUT retained for back-compat (existing SPA still
      // calls it); prefer the granular POST/DELETE pair below for new clients
      // so disjoint-category concurrent edits don't clobber each other.
      Method.PUT / "api" / "global" / "blocklists" ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            claims <- requireAdmin(req, auth).mapError(ApiError.Wrapped(_))
            r      <- parseBody[SetGlobalBlocklistsRequest](req)
            uid    <- actingUserId(claims)
            _      <- repo
              .setBlocklists(r.blocklistIds, uid)
              .mapError(ApiError.Db(_))
          } yield Response.ok
          handle.mapError(ErrorMapper.errorToResponse)
        },

      // #1456: idempotent add — re-adding a category that's already in the set
      // is a no-op 200 (ON CONFLICT DO NOTHING). Mirrors the allow/block-host
      // shape.
      Method.POST / "api" / "global" / "blocklists" / string("id") ->
        handler { (id: String, req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            claims <- requireAdmin(req, auth).mapError(ApiError.Wrapped(_))
            uid    <- actingUserId(claims)
            _      <- repo
              .addBlocklist(BlocklistId.unsafe(id), uid)
              .mapError(ApiError.Db(_))
          } yield Response.ok
          handle.mapError(ErrorMapper.errorToResponse)
        },

      // #1456: idempotent remove — deleting a category that's not in the set
      // is a no-op 200.
      Method.DELETE / "api" / "global" / "blocklists" / string("id") ->
        handler { (id: String, req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            _ <- requireAdmin(req, auth).mapError(ApiError.Wrapped(_))
            _ <- repo
              .removeBlocklist(BlocklistId.unsafe(id))
              .mapError(ApiError.Db(_))
          } yield Response.ok
          handle.mapError(ErrorMapper.errorToResponse)
        },

      // ── flat global flags (network lockdown + strict-IP floor) ────────────
      // Wholesale PUT retained for back-compat (existing SPA still calls it).
      Method.PUT / "api" / "global" / "flags" ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            _ <- requireAdmin(req, auth).mapError(ApiError.Wrapped(_))
            r <- parseBody[SetGlobalFlagsRequest](req)
            _ <- repo
              .setFlags(r.blocked, r.blockReason, r.blockIpOnly)
              .mapError(ApiError.Db(_))
          } yield Response.ok
          handle.mapError(ErrorMapper.errorToResponse)
        },

      // #1456: field-scoped PATCH so concurrent admin edits to *different*
      // flag fields don't clobber each other (the wholesale PUT is
      // last-write-wins). Omit a field → no change to that column.
      // `blockReason: null` → clear (the column is nullable); `blockReason:
      // "<value>"` → assign.
      Method.PATCH / "api" / "global" / "flags" ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            _                <- requireAdmin(req, auth).mapError(ApiError.Wrapped(_))
            body             <- req.body.asString.orElseFail(ApiError.BadRequest(""))
            obj              <- ZIO
              .fromEither(FieldPatch.parseObj(body))
              .mapError(ApiError.BadRequest(_))
            blockedPatch     <- ZIO
              .fromEither(FieldPatch.from[Boolean](obj, "blocked"))
              .mapError(ApiError.BadRequest(_))
            reasonPatch      <- ZIO
              .fromEither(FieldPatch.from[MacBlockReason](obj, "blockReason"))
              .mapError(ApiError.BadRequest(_))
            blockIpOnlyPatch <- ZIO
              .fromEither(FieldPatch.from[Boolean](obj, "blockIpOnly"))
              .mapError(ApiError.BadRequest(_))
            _                <- blockedPatch match {
              case FieldPatch.Cleared =>
                ZIO.fail(ApiError.BadRequest("blocked cannot be cleared"))
              case _                  => ZIO.unit
            }
            _                <- blockIpOnlyPatch match {
              case FieldPatch.Cleared =>
                ZIO.fail(ApiError.BadRequest("blockIpOnly cannot be cleared"))
              case _                  => ZIO.unit
            }
            blockedOpt = blockedPatch match {
              case FieldPatch.Set(v) => Some(v); case _ => None
            }
            blockIpOnlyOpt = blockIpOnlyPatch match {
              case FieldPatch.Set(v) => Some(v); case _ => None
            }
            reasonOpt = reasonPatch match {
              case FieldPatch.Absent  => None
              case FieldPatch.Cleared => Some(None)
              case FieldPatch.Set(v)  => Some(Some(v))
            }
            _                <- repo
              .patchFlags(blockedOpt, reasonOpt, blockIpOnlyOpt)
              .mapError(ApiError.Db(_))
          } yield Response.ok
          handle.mapError(ErrorMapper.errorToResponse)
        },
    )
  }
}
