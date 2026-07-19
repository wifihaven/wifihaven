package wifihaven.api.unit

import wifihaven.api.{PlainConfig, SupportConfig}
import wifihaven.api.support.{ClaudeCodeRoutines, CloudAgentDispatcher}
import wifihaven.api.support.CloudAgentDispatcher.Transport
import zio.json.ast.Json
import zio.test.*

/**
 * #2300 — pure pins for the config-selectable Claude Code Cloud support dispatcher. No DB / HTTP:
 * the full callback contract (webhook gating, token, agent endpoints) is exercised end-to-end,
 * transport-agnostically, by feature/SupportResponderSpec via the recorder — it is UNCHANGED by
 * #2300. Here we pin the three things #2300 actually adds:
 *
 *   1. the TRANSPORT SELECTION is an explicit function of `support.dispatcher` — claude-code-cloud
 *      selects the new path, managed-agents (and the default) selects the existing path, off
 *      selects disabled; 2. the CONFIG VALIDATION requires ONLY the selected transport's chain (and
 *      fails boot on an unknown dispatcher value) — no dark-by-default (#2265/#2266); 3. the Claude
 *      Code Cloud fire REQUEST SHAPE — the on-demand routine "API trigger" endpoint + `{"text": …}`
 *      body (the spiked trigger mechanism, cited in the PR).
 */
object ClaudeCodeDispatcherSpec extends ZIOSpecDefault {

  // A fully-configured responder cfg for each transport (the recorder stands in for the network in
  // feature tests, so no live key shape matters here — these only drive selection + validation).
  private val plainSet = PlainConfig(apiKey = "k", webhookSecret = "w")

  private val managedCfg = SupportConfig(
    responderEnabled = true,
    dispatcher = "managed-agents",
    plain = plainSet,
    anthropicApiKey = "sk-ant",
    claudeAgentId = "agent_x",
    claudeEnvironmentId = "env_x",
    agentTokenSecret = "secret",
    deploymentEnv = "staging",
  )

  private val claudeCodeCfg = SupportConfig(
    responderEnabled = true,
    dispatcher = "claude-code-cloud",
    plain = plainSet,
    claudeCodeRoutineId = "routine_x",
    claudeCodeRoutineToken = "sk-ant-oat01-routine-token",
    agentTokenSecret = "secret",
    deploymentEnv = "staging",
  )

  def spec = suite("Claude Code Cloud support dispatcher (#2300)")(
    // ── 1. transport selection ─────────────────────────────────────────────────
    test("dispatcher=claude-code-cloud selects the Claude Code Cloud transport") {
      assertTrue(CloudAgentDispatcher.transportFor(claudeCodeCfg) == Transport.ClaudeCodeCloud)
    },
    test("dispatcher=managed-agents selects the existing Managed Agents transport (unchanged)") {
      assertTrue(CloudAgentDispatcher.transportFor(managedCfg) == Transport.ManagedAgents)
    },
    test("the default dispatcher is managed-agents — pre-#2300 behavior is preserved") {
      assertTrue(
        SupportConfig().dispatcherTrimmed == "managed-agents",
        // A responder enabled with no explicit dispatcher stays on the existing path.
        CloudAgentDispatcher.transportFor(
          managedCfg.copy(dispatcher = SupportConfig().dispatcher),
        ) == Transport.ManagedAgents,
      )
    },
    test("responderEnabled=false selects Disabled regardless of dispatcher") {
      assertTrue(
        CloudAgentDispatcher.transportFor(
          claudeCodeCfg.copy(responderEnabled = false),
        ) == Transport.Disabled,
        CloudAgentDispatcher.transportFor(SupportConfig()) == Transport.Disabled,
      )
    },

    // ── 2. config validation: only the SELECTED transport's chain is required ────
    test("claude-code-cloud requires the routine id + token, NOT the Managed Agents triple") {
      val missing =
        SupportConfig(responderEnabled = true, dispatcher = "claude-code-cloud").missingRequiredKeys
      assertTrue(
        // the new transport's chain is required …
        missing.contains("support.claudeCodeRoutineId"),
        missing.contains("support.claudeCodeRoutineToken"),
        // … the common chain still is …
        missing.contains("support.plain.apiKey"),
        missing.contains("support.plain.webhookSecret"),
        missing.contains("support.agentTokenSecret"),
        missing.contains("support.deploymentEnv"),
        // … but the Managed Agents transport keys are NOT required when it isn't selected.
        !missing.contains("support.anthropicApiKey"),
        !missing.contains("support.claudeAgentId"),
        !missing.contains("support.claudeEnvironmentId"),
        // a fully-set claude-code-cloud cfg is clean.
        claudeCodeCfg.missingRequiredKeys.isEmpty,
      )
    },
    test("managed-agents requires its triple, NOT the routine keys (existing path unchanged)") {
      val missing =
        SupportConfig(responderEnabled = true, dispatcher = "managed-agents").missingRequiredKeys
      assertTrue(
        missing.contains("support.anthropicApiKey"),
        missing.contains("support.claudeAgentId"),
        missing.contains("support.claudeEnvironmentId"),
        !missing.contains("support.claudeCodeRoutineId"),
        !missing.contains("support.claudeCodeRoutineToken"),
        managedCfg.missingRequiredKeys.isEmpty,
      )
    },
    test("an unknown dispatcher value refuses to boot (explicit named value, #2265)") {
      val missing =
        SupportConfig(
          responderEnabled = true,
          dispatcher = "gpt-cloud",
          plain = plainSet,
          agentTokenSecret = "secret",
          deploymentEnv = "staging",
        ).missingRequiredKeys
      assertTrue(
        // even with the common chain fully set, an unknown transport is a boot failure that names
        // the valid set …
        missing.exists(m =>
          m.contains("support.dispatcher") && m.contains("managed-agents") &&
            m.contains("claude-code-cloud") && m.contains("gpt-cloud"),
        ),
        // … and it does NOT silently fall back to requiring one transport's keys.
        !missing.contains("support.anthropicApiKey"),
        !missing.contains("support.claudeCodeRoutineId"),
      )
    },
    test("dispatcher config is irrelevant when the responder is off (no keys required)") {
      assertTrue(
        SupportConfig(dispatcher = "claude-code-cloud").missingRequiredKeys.isEmpty,
        SupportConfig(dispatcher = "nonsense").missingRequiredKeys.isEmpty,
      )
    },

    // ── 3. the Claude Code Cloud fire request shape (spiked trigger mechanism) ──
    test("fireUrl targets the on-demand routine API-trigger endpoint") {
      assertTrue(
        ClaudeCodeRoutines.fireUrl("https://api.anthropic.com", "routine_x") ==
          "https://api.anthropic.com/v1/claude_code/routines/routine_x/fire",
        // a trailing slash on the base does not double up.
        ClaudeCodeRoutines.fireUrl("https://api.anthropic.com/", "routine_x") ==
          "https://api.anthropic.com/v1/claude_code/routines/routine_x/fire",
      )
    },
    test("firePayload carries the kickoff as the {\"text\": …} context field, JSON-safe") {
      val kickoff =
        "New support message on thread th_1.\n<customer_message>\nhi \"there\"\n</customer_message>"
      val payload = ClaudeCodeRoutines.firePayload(kickoff)
      val text    = Json.decoder
        .decodeJson(payload)
        .toOption
        .collect { case o: Json.Obj => o.fields.collectFirst { case ("text", Json.Str(s)) => s } }
        .flatten
      assertTrue(text.contains(kickoff))
    },
    test("firePayload caps text at the 65,536-char fire limit (a pathological input can't 400)") {
      val huge    = "a" * 70000
      val payload = ClaudeCodeRoutines.firePayload(huge)
      val text    = Json.decoder
        .decodeJson(payload)
        .toOption
        .collect { case o: Json.Obj => o.fields.collectFirst { case ("text", Json.Str(s)) => s } }
        .flatten
      assertTrue(text.exists(_.length == 65536))
    },
  )
}
