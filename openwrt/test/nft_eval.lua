-- nft_eval.lua — a small evaluator for the nft ruleset render.nft emits (#2901).
--
-- Test-only. It parses the rendered `table inet wifihaven { ... }` text and
-- walks a packet through the base chains of one hook, following jump / goto /
-- return, so a spec can ask "is this (mac, destination) dropped / sent to the
-- block page?" of a rendered ruleset without a kernel.
--
-- Destinations are symbolic: a packet carries the set of nft set NAMES its
-- destination address is in (what dnsmasq's nftset= callback would have put it
-- in), plus two flags for the only literal-address predicates render emits:
-- `fib daddr type local` and `ip6 daddr != ::1`.
--
-- Any token the evaluator does not model is an error, never skipped: a rule it
-- cannot read must fail the spec rather than be treated as a no-op.
--
--   local ev = nft_eval.parse(nft_text)
--   nft_eval.forward(ev, pkt)    → "drop" | "accept", dropping rule's comment
--   nft_eval.prerouting(ev, pkt) → "redirect" | "none"
--
-- pkt = { mac = "aa:..", family = "ip" | "ip6", sets = { [name] = true },
--         is_local = bool, is_loopback6 = bool, dport = 80, iif = "br-lan",
--         oif = "wan" }

local M = {}

local function tokenize(line)
  local toks, i, n = {}, 1, #line
  while i <= n do
    local c = line:sub(i, i)
    if c:match("%s") then
      i = i + 1
    elseif c == '"' then
      local j = line:find('"', i + 1, true)
      assert(j, "unterminated string in: " .. line)
      toks[#toks + 1] = line:sub(i, j)
      i = j + 1
    elseif c == "{" then
      -- Brace group (update key, anonymous set): kept as one token.
      local depth, j = 0, i
      repeat
        local ch = line:sub(j, j)
        if ch == "{" then depth = depth + 1 elseif ch == "}" then depth = depth - 1 end
        j = j + 1
      until depth == 0 or j > n
      assert(depth == 0, "unbalanced brace in: " .. line)
      toks[#toks + 1] = line:sub(i, j - 1)
      i = j
    else
      local j = i
      while j <= n and not line:sub(j, j):match('[%s"{]') do j = j + 1 end
      toks[#toks + 1] = line:sub(i, j - 1)
      i = j
    end
  end
  return toks
end

local function parse_rule(line)
  local t = tokenize(line)
  local rule = { text = line, matches = {}, verdict = nil }
  local i = 1
  local function need(k) local v = t[i + k]; assert(v, "truncated rule: " .. line); return v end
  while i <= #t do
    local w = t[i]
    if w == "fib" then
      assert(need(1) == "daddr" and need(2) == "type" and need(3) == "local", line)
      rule.matches[#rule.matches + 1] = { k = "local" }
      i = i + 4
    elseif w == "ether" then
      assert(need(1) == "saddr", line)
      local v = need(2)
      if v:sub(1, 1) == "@" then
        rule.matches[#rule.matches + 1] = { k = "mac_set", set = v:sub(2) }
      else
        assert(v:match("^%x%x:%x%x:%x%x:%x%x:%x%x:%x%x$"), "bad mac in: " .. line)
        rule.matches[#rule.matches + 1] = { k = "mac", mac = v:lower() }
      end
      i = i + 3
    elseif w == "ip" or w == "ip6" then
      assert(need(1) == "daddr", line)
      local neg, v = false, need(2)
      if v == "!=" then neg, v = true, need(3); i = i + 1 end
      if v:sub(1, 1) == "@" then
        rule.matches[#rule.matches + 1] = { k = "daddr_set", family = w, set = v:sub(2), neg = neg }
      elseif w == "ip6" and v == "::1" then
        rule.matches[#rule.matches + 1] = { k = "loopback6", neg = neg }
      else
        error("unsupported daddr operand '" .. v .. "' in: " .. line)
      end
      i = i + 3
    elseif w == "meta" then
      assert(need(1) == "nfproto", line)
      local f = need(2)
      assert(f == "ipv4" or f == "ipv6", line)
      rule.matches[#rule.matches + 1] = { k = "family", family = (f == "ipv4") and "ip" or "ip6" }
      i = i + 3
    elseif w == "tcp" then
      assert(need(1) == "dport", line)
      rule.matches[#rule.matches + 1] = { k = "dport", port = assert(tonumber(need(2)), line) }
      i = i + 3
    elseif w == "iifname" or w == "oifname" then
      rule.matches[#rule.matches + 1] = { k = w, name = need(1):gsub('"', "") }
      i = i + 2
    elseif w == "update" then
      -- `update @set { key limit ... }`: a per-flow log limiter. It sits only on
      -- verdict-less log rules, so whether it matches never changes a verdict.
      assert(need(1):sub(1, 1) == "@" and need(2):sub(1, 1) == "{", line)
      i = i + 3
    elseif w == "log" then
      assert(need(1) == "prefix", line)
      i = i + 3
    elseif w == "counter" then
      i = i + 1
      if t[i] == "packets" then i = i + 4 end
    elseif w == "comment" then
      rule.comment = need(1):gsub('"', "")
      i = i + 2
    elseif w == "drop" or w == "accept" or w == "return" then
      assert(not rule.verdict, "two verdicts in: " .. line)
      rule.verdict = { k = w }
      i = i + 1
    elseif w == "jump" or w == "goto" then
      assert(not rule.verdict, "two verdicts in: " .. line)
      rule.verdict = { k = w, chain = need(1) }
      i = i + 2
    elseif w == "dnat" then
      assert(need(1) == "ip" and need(2) == "to", line)
      -- `dnat ip to` in an inet table never fires on an IPv6 packet: the
      -- kernel's nft_nat_inet_eval skips a NAT whose family differs from the
      -- packet's, so the rule falls through with no verdict.
      rule.matches[#rule.matches + 1] = { k = "family", family = "ip" }
      rule.verdict = { k = "nat", target = need(3) }
      i = i + 4
    elseif w == "redirect" then
      assert(need(1) == "to", line)
      rule.verdict = { k = "nat", target = need(2) }
      i = i + 3
    else
      error("nft_eval: unsupported token '" .. w .. "' in: " .. line)
    end
  end
  return rule
end

local PRIO_NAMES = { dstnat = -100, filter = 0, srcnat = 100 }

-- parse(text) → { chains = { [name] = chain }, sets = { [name] = {elements} } }
function M.parse(text)
  local ev = { chains = {}, sets = {} }
  local cur_chain, cur_set
  for raw in (text .. "\n"):gmatch("([^\n]*)\n") do
    local line = raw:gsub("^%s+", ""):gsub("%s+$", "")
    if line == "" or line:sub(1, 1) == "#" then
      -- skip
    elseif cur_chain then
      if line == "}" then
        cur_chain = nil
      elseif line:match("^type ") then
        local typ, hook, prio = line:match("^type (%S+) hook (%S+) priority ([^;]+);")
        assert(typ, "bad chain header: " .. line)
        cur_chain.type, cur_chain.hook = typ, hook
        cur_chain.priority = tonumber(prio) or assert(PRIO_NAMES[prio], line)
      else
        cur_chain.rules[#cur_chain.rules + 1] = parse_rule(line)
      end
    elseif cur_set then
      if line == "}" then
        cur_set = nil
      else
        local elems = line:match("^elements = {(.*)}$")
        if elems then
          for e in elems:gmatch("[^,%s]+") do cur_set.elements[e:lower()] = true end
        end
      end
    else
      local cname = line:match("^chain (%S+) {$")
      local sname = line:match("^set (%S+) {$")
      if cname then
        assert(not ev.chains[cname], "duplicate chain " .. cname)
        cur_chain = { name = cname, rules = {} }
        ev.chains[cname] = cur_chain
      elseif sname then
        cur_set = { elements = {} }
        ev.sets[sname] = cur_set
      end
    end
  end
  return ev
end

local function matches(ev, m, pkt)
  local k = m.k
  if k == "local" then return pkt.is_local == true
  elseif k == "mac" then return pkt.mac == m.mac
  elseif k == "mac_set" then
    local s = assert(ev.sets[m.set], "undeclared set @" .. m.set)
    return s.elements[pkt.mac] == true
  elseif k == "daddr_set" then
    assert(ev.sets[m.set], "undeclared set @" .. m.set)
    if pkt.family ~= m.family then return false end
    return (pkt.sets[m.set] == true) ~= m.neg
  elseif k == "loopback6" then
    if pkt.family ~= "ip6" then return false end
    return (pkt.is_loopback6 == true) ~= m.neg
  elseif k == "family" then return pkt.family == m.family
  elseif k == "dport" then return pkt.dport == m.port
  elseif k == "iifname" then return pkt.iif == m.name
  elseif k == "oifname" then return pkt.oif == m.name
  end
  error("unknown match " .. tostring(k))
end

-- Run one base chain. Returns the terminal verdict kind ("drop", "accept",
-- "nat") or nil when the packet falls off the base chain (policy accept), and
-- the comment of the rule that gave the verdict.
local function run_base(ev, base, pkt)
  local stack = {}
  local chain, idx = base, 1
  local depth = 0
  while true do
    local rule = chain.rules[idx]
    if not rule then
      if #stack == 0 then return nil end
      local top = table.remove(stack)
      chain, idx = top.chain, top.idx
    else
      idx = idx + 1
      local ok = true
      for _, m in ipairs(rule.matches) do
        if not matches(ev, m, pkt) then ok = false; break end
      end
      local v = ok and rule.verdict
      if v then
        if v.k == "drop" or v.k == "accept" or v.k == "nat" then return v.k, rule.comment end
        if v.k == "return" then
          if #stack == 0 then return nil end
          local top = table.remove(stack)
          chain, idx = top.chain, top.idx
        else
          local target = assert(ev.chains[v.chain], "jump to undeclared chain " .. v.chain)
          assert(not target.hook, "jump to base chain " .. v.chain)
          depth = depth + 1
          assert(depth < 64, "jump loop")
          if v.k == "jump" then stack[#stack + 1] = { chain = chain, idx = idx } end
          chain, idx = target, 1
        end
      end
    end
  end
end

local function base_chains(ev, typ, hook)
  local out = {}
  for _, c in pairs(ev.chains) do
    if c.type == typ and c.hook == hook then out[#out + 1] = c end
  end
  table.sort(out, function(a, b)
    if a.priority ~= b.priority then return a.priority < b.priority end
    return a.name < b.name
  end)
  return out
end

-- A forward packet is dropped iff some forward filter base chain drops it; an
-- accept ends only its own base chain. Also returns the dropping rule's comment
-- (render's `wh_drop:<mac>:<reason>`, the label drop counters and events use).
function M.forward(ev, pkt)
  for _, c in ipairs(base_chains(ev, "filter", "forward")) do
    local v, comment = run_base(ev, c, pkt)
    if v == "drop" then return "drop", comment end
  end
  return "accept"
end

-- The first nat base chain to DNAT/redirect wins; every render target is the
-- local block-page listener, so only whether it fires matters.
function M.prerouting(ev, pkt)
  for _, c in ipairs(base_chains(ev, "nat", "prerouting")) do
    if run_base(ev, c, pkt) == "nat" then return "redirect" end
  end
  return "none"
end

M.tokenize = tokenize
return M
