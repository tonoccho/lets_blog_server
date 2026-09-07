# Security Policy

## Reporting Security Vulnerabilities

If you discover a security vulnerability in this project, please **do not open a public issue on the tracker**. Instead, please report it responsibly by sending an email to **s.tonouchi@gmail.com** with:

1. Description of the vulnerability
2. Steps to reproduce the issue
3. Potential impact
4. Suggested fix (if available)

Please allow up to 7 days for an initial response. We will work with you to verify the vulnerability and develop a fix.

## Security Updates

Security updates will be released as soon as possible after a vulnerability is confirmed. We recommend:

1. Subscribing to GitHub security alerts for this repository
2. Regularly updating your dependencies
3. Monitoring this security policy for updates

## Supported Versions

| Version | Status | Security Updates |
|---------|--------|------------------|
| 0.1.x   | Active | Yes              |

## Security Scanning

**There is no automated security scanning.** This repository has no CI (see
[README → 品質の担保](README.md#品質の担保)), so nothing scans on a schedule or on push.

Scanning is run by hand instead, when dependencies change and roughly monthly:

```bash
cd apps/web       && npm audit --audit-level=moderate
cd apps/extension && npm audit --audit-level=moderate
./gradlew dependencyCheckAnalyze
```

Static analysis is limited to what the ordinary build gives — ESLint on the frontend, the
JVM build's own checks. There is no CodeQL equivalent; that gap is a known consequence of
not running CI, not an oversight.

See [Security Scanning Documentation](docs/SECURITY_SCANNING.md) for the full picture,
including what was removed and why.

## Authentication and Authorization Model

Authentication is handled entirely by **Keycloak (OIDC)**. There is no application-managed login
path: the header-based self-declared identity used before issue #566 has been removed
(see [ADR-0002](docs/adr/0002-keycloak-oidc.md)).

| Client | Flow |
|---|---|
| Web admin UI (Next.js) | Authorization Code + PKCE, via NextAuth's Keycloak provider (#564) |
| VSCode extension | Device Authorization Grant (#565) |
| Service-to-service | Client Credentials Grant, `letsblog-services` client (#567, [ADR-0005](docs/adr/0005-service-to-service-client-credentials.md)) |

### Where the auth gate lives

**Each service enforces its own authentication gate** in its `SecurityConfig`
(`anyRequest().authenticated()`, with a short explicit `PUBLIC_PATHS` list), *not* the gateway
([ADR-0008](docs/adr/0008-auth-gate-in-each-service-security-config.md)). A request that bypasses the
gateway and reaches a service container directly is still rejected without a valid JWT.

The only **application** endpoints reachable without a token are:

- `/actuator/**` (health/info/metrics; used by docker healthchecks and the gateway's aggregate health)
- API docs (`/v3/api-docs/**`, `/swagger-ui/**`)
- `/api/auth/setup-status` and `/api/auth/setup` — first-run admin creation, which by definition has
  to work before anyone can log in (identity-service, moved there in #583)

### Paths the reverse proxy forwards outside that gate

The gate above only covers what reaches `web` or `gateway`. `infra/nginx/conf.d/default.conf`
also forwards a number of paths straight to bundled third-party containers. Those requests never
reach a Spring `SecurityConfig`, so each one is only as protected as the tool behind it. This is
the full list — everything else under `/` and `/api/` goes through the gate above.

| Path | Forwarded to | What guards it |
|---|---|---|
| `/nginx-health` | nginx itself | Nothing. Returns a static `200`; carries no data |
| `/auth/` | Keycloak | Keycloak's own login. Public by design — it *is* the login endpoint (#559) |
| `/phpmyadmin/` | phpMyAdmin | phpMyAdmin's own login screen (`auth_type: cookie`), using MySQL credentials. The container is deliberately given **no** `PMA_USER` / `PMA_PASSWORD`: those switch it to `auth_type: config`, which hands every visitor an already-connected MySQL session (#978) |
| `/penpot/` | Penpot (`penpot-frontend`) | Penpot's own login. The prefix is stripped before forwarding, and bare `/penpot` redirects to `/penpot/`; Penpot's `index.html` references its assets relatively, so it only assembles under the trailing slash (#1012). This adds no exposure that did not already exist: `penpot-frontend` is also published directly on host port `9001` |
| `/sites/<slug>/**` | WordPress | Nothing for published pages — they are public blog content by design. `wp-admin` behind them is guarded by WordPress's own login |
| `/drawio/` | drawio | **Nothing — deliberately (#979).** The VSCode extension loads `/drawio/?embed=1&…` into a webview iframe (`apps/extension/src/diagramEditorPanel.ts`), which runs outside `lbs-net`, so it cannot use container-to-container calls like the other bundled tools. draw.io is a self-contained static editor that touches none of this repository's data; diagrams are saved by the extension through the authenticated `/api/**`. `/comfyui/` and `/plantuml/` used to sit on this row and were **removed** from the reverse proxy in #979: nothing in `web` or the extension opened them, and unauthenticated they allowed arbitrary GPU workflows and arbitrary server-side rendering |

A path added to the reverse proxy that does not terminate in `web` or `gateway` belongs in this
table. If it has no authentication of its own, it does not belong on the reverse proxy at all.

### Authorization

Authorization (who may do what) is procedural, per endpoint: `requireAdmin()`,
`requireProjectMemberOrAdmin()`, and their variants. Issue #830 went through every endpoint and
decided whether it needs authorization; endpoints deliberately left at "any authenticated user" carry
a `認可不要: <reason>` comment on the handler.

This is enforced mechanically: `AuthorizationCoverageContract` (in `packages/lbs-common` test fixtures)
fails the build if an endpoint without an authorization call — and without that comment — appears in
any service. The allow-list is empty for all nine services. The current state is documented in
[docs/AUTHORIZATION_MATRIX.md](docs/AUTHORIZATION_MATRIX.md).

### Deactivated users

Deactivating a user does **not** revoke already-issued access tokens (Keycloak only stops issuing new
ones). Each service therefore re-resolves the actor through identity-service on every request and
treats a disabled user as "no actor", so authorization fails closed (#816). identity-service returning
401/403 is treated as an authorization result, not as an outage (#829).

## Best Practices

When contributing to this project, please follow these security best practices:

1. **Never commit secrets**: Do not commit passwords, API keys, or other sensitive information
2. **Use environment variables**: Store sensitive configuration in `.env` files (which are gitignored)
3. **Validate input**: All user input should be validated and sanitized
4. **Keep dependencies updated**: Regularly update your dependencies to patch known vulnerabilities
5. **Review security warnings**: Address any security warnings reported by automated tools
6. **Code review**: Security-sensitive changes should be carefully reviewed

## Security Headers

The admin web application (`apps/web`) sends the following headers on every response it
serves. They are declared in `apps/web/next.config.ts` (`headers()`), which Next.js applies
before `proxy.ts` runs, so the authentication gate's redirect responses carry them too:

- `X-Content-Type-Options: nosniff` — no MIME sniffing
- `X-Frame-Options: SAMEORIGIN` — the admin screens cannot be framed by another site
- `Referrer-Policy: strict-origin-when-cross-origin` — no path/query leak across origins
- `Strict-Transport-Security: max-age=300` — deliberately short. This environment uses a
  self-signed certificate (`scripts/generate-certs.sh`), and a long `max-age` would make the
  certificate warning unbypassable after the certificate is regenerated.

The headers are set in the application rather than in the reverse proxy. nginx's `add_header`
appends unconditionally, so applying them to the whole 443 server block would duplicate or
contradict the headers that Keycloak, WordPress, phpMyAdmin and draw.io emit themselves, and
`X-Frame-Options` on `/drawio/` would break the draw.io editor that the VSCode extension loads
in a webview iframe. Those upstreams keep their own header policy.

Not implemented, and why:

- **Content-Security-Policy** — not sent. The admin screens embed draw.io, Penpot and PlantUML
  through iframes and images, and the development server needs `eval` and inline scripts, so a
  policy that is both correct and useful cannot be written without a dedicated effort. (The
  article preview has its own CSP, applied to the previewed HTML only.)
- **CORS** — there is no explicit CORS configuration anywhere in this repository (no
  `CorsConfiguration`, `addCorsMappings`, `CorsWebFilter`, `@CrossOrigin`, nor a hand-written
  `Access-Control-Allow-Origin`). The browser reaches the web app and the API through the same
  origin (the reverse proxy publishes both under one host), so cross-origin requests are
  rejected by the browser's same-origin policy by default and no allow-list is granted.

HTTPS enforcement and session handling are unchanged: the reverse proxy redirects port 80 to
443 (`infra/nginx/conf.d/default.conf`), and sessions are handled by NextAuth.js with
Keycloak-issued tokens held in an encrypted, HttpOnly session cookie.

## Dependency Management

Dependency updates are **not automated**. Dependabot does not run here (it is a GitHub
service, and this repository moved to a self-hosted GitLab CE in 2026-09), and no
replacement has been put in its place.

Updates are raised as Issues and applied by hand. Vulnerability fixes are treated as
`priority::P0`. The procedure, including which update sizes may be batched, is in
[docs/DEPENDENCY_UPDATE_POLICY.md](docs/DEPENDENCY_UPDATE_POLICY.md).

## What enforces security practices

There is no third-party security service wired in. What the repository does enforce, it
enforces on the commit and merge path:

| Mechanism | Where |
| --- | --- |
| Phase separation, test-first, no silenced tests | `scripts/git-hooks/pre-commit` |
| The above plus read-only stages, merge method, label integrity | `.claude/hooks/guard.py` |
| Changed-code branch coverage at 90% before a Merge Request opens | `scripts/check-changed-coverage.py` |

The git hook applies to every committer, agent or human.

## Questions?

If you have questions about security practices or policies, please contact the project maintainer at s.tonouchi@gmail.com.
