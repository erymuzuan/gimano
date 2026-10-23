# Back office: feedback, crash reports and AI agents

Maintainers triage what people send from the app (feedback, crash reports) in a Blazor back office. AI agents
(Claude Code on the build Mac) read the same data through an MCP server, propose fixes as draft PRs and draft replies.
People stay in charge of everything that leaves Gimano or touches a reporter.

Design canvas: https://claude.ai/artifact/XBcon9bVauxY6gVjBf2Y8T

```
phone ──(consented, scrubbed)──▶ Api /api/v1/feedback, /crash-reports ──▶ Postgres ◀── Worker (retention sweep)
                                                                              ▲
             maintainer ──(passkey / password + TOTP)──▶ BackOffice (Blazor) ─┤
                                                                              │
 Claude Code ──(agt_ token, Tailscale)──▶ Api /mcp  (gimano-backoffice MCP) ──┤
                                    └──▶ Api /admin/v1 (REST twin)          ──┘
          └──▶ GitHub: agent/* branch + draft PR ──▶ maintainer merges ──▶ release ──▶ mark fixed (build n)
```

## Pieces

| Piece | Where | |
|---|---|---|
| Intake | `Gimano.Api` `POST /api/v1/feedback`, `POST /api/v1/crash-reports` | contacts sealed on arrival (`ContactProtector`, AES-GCM) |
| Data + rules | `Gimano.Core/BackOffice/` (`TriageService`, `ReportIntake`, `CrashSignature`) | one service used by all three front doors |
| Back office UI | `Gimano.BackOffice` (Blazor, Interactive Server per page, Identity + passkeys) | port 5090 |
| Agent API | `Gimano.Api` `/admin/v1/*` and `/mcp` | `agt_` bearer tokens with scopes |
| Retention | `Gimano.Worker` `BackOfficeRetention` (hourly) | deletes expired contacts, events, tokens, audit rows |

## Crash grouping

`signature = sha256(platform \n exception \n top 5 app frames)`. App frames are those containing `my.gimano.` /
`Gimano`; line numbers, `0x…` addresses, `+ offset` suffixes and `$lambda$n` / `$1` suffixes are removed, so the same
bug keeps its signature across builds. Without app frames, the first 5 frames are used.

A group's lifecycle is `new → triaged → fixing → fixed`, plus `ignored`. Marking it `fixed` needs `fixedInBuild`: when an
event arrives from that build or a later one, the group becomes **`regressed`** automatically. Older builds still in use
don't reopen it. When a group is first marked fixed, every open feedback item linked to it (`crashSignature`) gets a
**reply draft** that a maintainer approves.

Events keep no user id, device token or location, and are deleted after 90 days. The group keeps its counts.

## Who sees what

| Field | Public GitHub issue | Agent token | Maintainer |
|---|---|---|---|
| Message, category, screen, rating, app version | yes | yes, fenced as untrusted | yes |
| Device model, screen size, OS | no | yes | yes |
| Area (2 dp ≈ 1 km) | no | yes | yes |
| Crash stack (scrubbed on the phone) | no | yes | yes |
| Screenshot | no | only if a maintainer shares that one image | yes |
| Email / WhatsApp | never | never (flags only) | reveal with a reason, audited |
| User id, device token, IP address | never | never | never |

- **Contacts** are stored only as AES-GCM ciphertext (`report_contacts`), with the key in host config
  (`Contact:EncryptionKey`). No API response contains them. Maintainers can unseal one from the back office after
  giving a reason, and the reason goes to the audit log. A contact is deleted 30 days after its report closes and after
  90 days at most. Reporters can withdraw it with `DELETE /api/v1/feedback/{id}/contact`, and `DELETE /me` removes it.
  Crash-report contacts can't be linked to an account, so only the 90-day limit applies to them.
- Without `Contact:EncryptionKey`, the API **drops** volunteered contacts instead of storing them.

## Agent access

Tokens are minted in the back office (**Agent access**). They are shown once, live at most 8 hours, and are stored
as sha256. They can be revoked at any time.

| Scope | Allows |
|---|---|
| `read` | queue, feedback, crash groups/events, builds, runs; screenshots only when shared |
| `triage:write` | status, severity, category, duplicate-of, crash link, note, github.com link, analysis, fixed-in build |
| `runs:write` | start a run, log steps, set branch (`agent/*` only), PR URL, summary, status (not `done`) |
| `replies:draft` | queue an Inbox reply for a maintainer to approve |

No scope reveals contacts, mints tokens, sends replies, marks runs done, registers builds, merges or deploys.

**Network:** `/admin` and `/mcp` answer 404 to requests that came through the public Cloudflare edge
(`CF-Connecting-IP` / `CF-Ray`). Agents use Tailscale (`http://100.101.150.9:5080`). `Admin:AllowedNetworks`
(CIDRs) can tighten this further, and `Admin:Enabled=false` turns both endpoints off.

**Prompt injection:** reporter-written text from the MCP tools is wrapped in `<untrusted-{nonce}>…</untrusted-{nonce}>`
with a notice. None of the tools can reach outside Gimano, so the worst a hostile message can do is produce a bad
draft, and a person reads every draft before it goes out.

### MCP server `gimano-backoffice`

Streamable HTTP, stateless, at `POST /mcp`. Add it to Claude Code on the build Mac:

```bash
claude mcp add --transport http gimano-backoffice http://100.101.150.9:5080/mcp \
  --header "Authorization: Bearer agt_…"
```

| Tool | Scope | |
|---|---|---|
| `queue` | read | open feedback + crash groups, regressions first |
| `feedback_search`, `feedback_get` | read | filters: status, category, screen, platform, text, sinceDays |
| `feedback_screenshot` | read | image content; 403 unless shared |
| `crash_groups`, `crash_group`, `crash_events`, `crash_linked_feedback` | read | stack + distributions |
| `builds_resolve` | read | app build → git commit + symbols location |
| `feedback_triage`, `crash_triage` | triage:write | |
| `reply_draft` | replies:draft | held in the Replies outbox |
| `run_start`, `run_log`, `run_update` | runs:write | |

The REST twin is `/admin/v1` (`/queue`, `/feedback`, `/feedback/{id}`, `/feedback/{id}/screenshot`, `PATCH /feedback/{id}`,
`POST /feedback/{id}/replies`, `/crash-groups`, `/crash-groups/{sig}`, `/events`, `/feedback`, `PATCH /crash-groups/{sig}`,
`/builds`, `/builds/resolve`, `/runs`, `POST /runs`, `POST /runs/{id}/steps`, `PATCH /runs/{id}`); see `/scalar/v1`.

### The fix loop

1. A maintainer opens a crash group, clicks **Agent prompt** and pastes it into Claude Code in the repo checkout.
2. The agent runs `run_start`, reads `crash_group` / `crash_events`, runs `builds_resolve` and checks out that commit.
3. It reproduces the bug with a failing unit test, fixes it on `agent/<run>-<slug>`, runs the tests and opens a
   **draft** PR. It records the branch and PR (`run_update`) and its analysis (`crash_triage analysis=…`), then
   sets the run to `waiting_for_review`.
4. The maintainer reviews and merges on GitHub. Protect `main` so agent credentials can't merge.
5. After the release, the maintainer registers the build under **Releases** and marks the group `fixed` with its build
   number. Linked reporters get reply drafts. Approving one puts a `feedback_status` item in the reporter's in-app Inbox.

## Maintainer sign-in

ASP.NET Core Identity in the `backoffice` schema of the Gimano database. Only emails in `BackOffice:Maintainers` can
register or stay signed in; removing an email signs that person out on their next request. Passkeys need a secure
context, so serve the back office over HTTPS, e.g. `tailscale serve --bg --https=443 http://localhost:5090` on WS556,
then open `https://<ws556>.<tailnet>.ts.net`. Over plain HTTP, use a password plus an authenticator app (2FA).

## Retention (worker, hourly)

| Data | Kept |
|---|---|
| Contact details | 30 days after the report closes, 90 days max |
| Crash events | 90 days (group counts stay) |
| Inbox items | 90 days |
| Agent tokens | deleted 30 days after expiry or revocation |
| Audit log | 1 year |
