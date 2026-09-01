# Security Policy

## Reporting Security Vulnerabilities

If you discover a security vulnerability in this project, please **do not open a public GitHub issue**. Instead, please report it responsibly by sending an email to **s.tonouchi@gmail.com** with:

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

This project uses automated security scanning to detect vulnerabilities early:

### Dependency Scanning
- **Dependabot**: Automatically scans for vulnerable dependencies
- **npm audit**: Weekly scans for JavaScript dependencies
- **Gradle dependencyCheck**: Weekly scans for Java dependencies

### Code Analysis
- **CodeQL**: Continuous static analysis for Java and JavaScript/TypeScript code
- **GitHub Security Alerts**: Automated vulnerability detection for dependencies

### License Compliance
- Automated license scanning to ensure compliance with open source license requirements

See [Security Scanning Documentation](docs/SECURITY_SCANNING.md) for more details.

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
| `/penpot` | Penpot | Penpot's own login |
| `/sites/<slug>/**` | WordPress | Nothing for published pages — they are public blog content by design. `wp-admin` behind them is guarded by WordPress's own login |
| `/comfyui/`, `/plantuml/`, `/drawio/` | ComfyUI / PlantUML / drawio | **Nothing.** Documented as internal-only but in fact forwarded unauthenticated; tracked in #979 |

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

The project includes security configurations:
- Secure HTTP headers in web responses (via backend and frontend middleware)
- HTTPS enforcement for API communication
- CORS policies to restrict API access
- Secure session handling with NextAuth.js

## Dependency Management

This project uses Dependabot to automatically monitor and manage dependency updates:

- **npm**: Frontend, Extension, and SDK dependencies
- **Gradle**: Backend Java dependencies
- **GitHub Actions**: CI/CD action versions

Dependabot creates pull requests for updates, allowing for review before merging.

## Third-Party Security Tools

The project integrates with GitHub's built-in security features:

1. **Dependabot**: Automatic dependency vulnerability detection
2. **CodeQL**: Static code analysis
3. **GitHub Security Alerts**: Vulnerability notifications

## Questions?

If you have questions about security practices or policies, please contact the project maintainer at s.tonouchi@gmail.com.
