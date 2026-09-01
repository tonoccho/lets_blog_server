# Security Scanning Process

This document describes the automated security scanning infrastructure for the Let's Blog Server project.

## Overview

The project uses multiple layers of automated security scanning to detect vulnerabilities, insecure code patterns, and dependency issues:

1. **Dependency Scanning**: Detects known vulnerabilities in dependencies
2. **SAST (Static Application Security Testing)**: Analyzes code for security issues
3. **License Compliance**: Ensures dependencies comply with open source licenses
4. **Software Bill of Materials (SBOM)**: Tracks all project dependencies

## Scanning Tools

### 1. Dependabot

**Purpose**: Automated dependency vulnerability detection and updates

**Configuration**: `.github/dependabot.yml`

**Packages Monitored**:
- npm (web, extension, sdk)
- Gradle (API backend)
- GitHub Actions
- Docker

**Frequency**: Weekly (Mondays at 3:00 AM UTC)

**How it Works**:
- Dependabot automatically creates pull requests for dependency updates
- Updates are categorized by severity
- Direct dependencies are prioritized
- Pull requests are assigned to maintainers for review

**Reviewing Dependabot PRs**:
1. Check the security advisory details
2. Review the changelog for breaking changes
3. Run tests to ensure compatibility
4. Merge when safe

### 2. npm audit (Frontend Dependencies)

**Purpose**: Scans Node.js project dependencies for known security vulnerabilities

**Runs**: Every push to main/develop branches, weekly scheduled

**Workflow**: `.github/workflows/security-scanning.yml`

**Output**:
- Audit report artifacts (JSON format)
- CI check failure for moderate+ severity issues

**Local Usage**:
```bash
cd apps/web
npm audit              # Show vulnerabilities
npm audit fix         # Attempt automatic fixes
npm audit fix --force # Force fixes (may introduce breaking changes)
```

### 3. Gradle Dependency Check (API Backend)

**Purpose**: Analyzes Java dependencies for known vulnerabilities

**Runs**: Every push to main/develop branches, weekly scheduled

**Workflow**: `.github/workflows/security-scanning.yml`

**Configuration**: `api/build.gradle` (OWASP Dependency-Check plugin)

**Output**:
- HTML vulnerability report
- CI artifacts for audit trail

**Local Usage**:
```bash
cd api
./gradlew dependencyCheck
# Report: build/reports/dependency-check-report.html
```

### 4. CodeQL Analysis

**Purpose**: Static code analysis for Java and JavaScript/TypeScript

**Supported Languages**:
- Java (backend)
- JavaScript/TypeScript (frontend)

**Runs**:
- On every push to main/develop
- On every pull request
- Daily scheduled scan (2:00 AM UTC)

**Workflow**: `.github/workflows/codeql-analysis.yml`

**Query Sets**: security-and-quality

**Results**: Available in GitHub Security tab → Code scanning alerts

**Common Issues Detected**:
- SQL injection vulnerabilities
- Cross-site scripting (XSS)
- Unsafe deserialization
- Hardcoded credentials
- Path traversal vulnerabilities
- Insecure randomness

### 5. License Compliance Scanning

**Purpose**: Ensures project dependencies comply with acceptable open source licenses

**Runs**: Every push to main/develop branches, weekly scheduled

**Allowed Licenses**:
- MIT
- Apache-2.0
- BSD-2-Clause
- BSD-3-Clause
- ISC
- LGPL-2.1
- LGPL-3.0
- MPL-2.0

**Workflow**: `.github/workflows/security-scanning.yml`

**How to Add Approved Licenses**:
Edit the security-scanning.yml workflow and update the `--onlyAllow` parameter.

### 6. Software Bill of Materials (SBOM)

**Purpose**: Generates and tracks a complete list of project dependencies

**Format**: CycloneDX JSON

**Runs**: Every push to main/develop branches, weekly scheduled

**Use Cases**:
- Supply chain security audits
- Regulatory compliance (SLSA, NIST)
- Vulnerability tracking

## GitHub Security Settings

### Enabling GitHub Security Features

The project uses GitHub's built-in security features:

1. **Dependabot Alerts**: Enabled
   - Location: Settings → Code security & analysis
   - Monitors for vulnerable dependencies automatically

2. **Dependabot Security Updates**: Enabled
   - Automatically creates PRs for security updates
   - Can be configured per package ecosystem

3. **Code Scanning**: Enabled (CodeQL)
   - Location: Settings → Code security & analysis
   - Results in Security tab → Code scanning alerts

4. **Secret Scanning**: Enabled
   - Detects accidentally committed secrets
   - Location: Settings → Code security & analysis

## Handling Security Findings

### For Developers

When security issues are found:

1. **Immediate Action**: Review the issue
   - Evaluate the severity and impact
   - Check if it affects your code path

2. **Plan a Fix**:
   - Update dependencies to patched versions
   - Or apply a code fix if it's a custom issue

3. **Test Thoroughly**:
   - Run full test suite
   - Perform manual testing if needed
   - Check for breaking changes

4. **Submit PR**:
   - Reference the security issue in the PR description
   - Include reproduction steps if applicable
   - Document the fix approach

### For Maintainers

When reviewing security PRs:

1. Verify the fix addresses the root cause
2. Check for performance impacts
3. Ensure no new vulnerabilities are introduced
4. Merge quickly to production

## Responding to Incidents

### Vulnerability Disclosure

If a vulnerability is discovered:

1. **Report responsibly**: Contact s.tonouchi@gmail.com
2. **Do not disclose publicly** until a fix is available
3. **Provide details**: Description, reproduction steps, impact
4. **Allow time for fix**: 7 days for initial response

### Release Process

1. **Develop fix** in a private branch
2. **Test thoroughly** on staging environment
3. **Release patch version** with security fix
4. **Publish security advisory** after release
5. **Update dependencies** across dependent projects

## Continuous Monitoring

### Automated Alerts

The project monitors security threats:

- **Dependabot Alerts**: Email notifications for new vulnerabilities
- **CodeQL Alerts**: In PR/GitHub Security tab
- **GitHub Security Alerts**: Configured in organization settings

### Manual Review

Regular security reviews recommended:

```bash
# Local dependency audits
cd apps/web && npm audit
cd apps/extension && npm audit
cd api && ./gradlew dependencyCheck

# Check for outdated dependencies
npm outdated
```

## Best Practices

1. **Keep Dependencies Updated**: Don't delay security updates
2. **Review Dependabot PRs Promptly**: Security fixes should be merged quickly
3. **Monitor Alerts**: Set up GitHub notifications for this repository
4. **Contribute Security Fixes**: If you find an issue, help fix it
5. **Use Security Tools Locally**: Run audits before submitting PRs

## Troubleshooting

### npm audit Shows False Positives

Some vulnerabilities may not affect your code path:

```bash
npm audit --production  # Only check production dependencies
npm audit fix --dry-run # Preview fixes before applying
```

### CodeQL Creates Excessive Alerts

1. Review each alert carefully
2. Suppress false positives with inline comments:
   ```java
   // lgtm[js/cross-site-scripting]
   ```
3. Or update CodeQL configuration in `.github/codeql-config.yml`

### Dependabot Fails to Auto-Merge

Some updates may require manual intervention due to:
- Breaking changes
- Conflicting constraints
- Custom dependencies

Merge manually after testing.

## References

- [GitHub Security Documentation](https://docs.github.com/en/code-security)
- [CodeQL Documentation](https://codeql.github.com/docs/)
- [Dependabot Documentation](https://docs.github.com/en/code-security/dependabot)
- [OWASP Dependency Check](https://owasp.org/www-project-dependency-check/)
- [CycloneDX SBOM Format](https://cyclonedx.org/)

## Questions or Issues?

For questions about security scanning, please contact the maintainers or open an issue on GitHub.
