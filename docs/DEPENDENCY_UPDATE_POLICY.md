# Dependency Update Policy

This document outlines the automated dependency update strategy for the Let's Blog Server project.

## Overview

We use **Dependabot** to automatically check for dependency updates and create pull requests. This helps us stay current with security patches, bug fixes, and new features while maintaining code quality and stability.

## Automated Update Schedule

### Update Frequency

- **Interval**: Weekly (every Monday)
- **Time**: Varies by ecosystem to stagger updates:
  - npm (web): 03:00 UTC
  - npm (extension): 03:00 UTC
  - npm (SDK): 03:00 UTC
  - gradle (API): 03:30 UTC
  - GitHub Actions: 04:00 UTC
  - Docker: 04:30 UTC

### Rationale

Weekly updates strike a balance between:
- Staying current with security patches and features
- Reducing the frequency of dependency updates
- Allowing time for testing and review

## Configured Ecosystems

### 1. npm (web, extension, SDK)

- **Directories**: `/web`, `/extension`, `/packages/api-client`
- **Dependencies**: All (production and development)
- **Commit prefix**: `chore`
- **Open PR limit**: 10 (maximum concurrent Dependabot PRs)

### 2. Gradle (API)

- **Directory**: `/api`
- **Dependencies**: All (production and development)
- **Commit prefix**: `chore`
- **Open PR limit**: 10

### 3. GitHub Actions

- **Directory**: Repository root
- **Dependencies**: Workflow dependencies
- **Commit prefix**: `ci`
- **Open PR limit**: 10

### 4. Docker

- **Directory**: Repository root
- **Dependencies**: Base images in Dockerfile
- **Commit prefix**: `ci`
- **Open PR limit**: 10

## Auto-Merge Policy

### Enabled for

- **Patch updates** (e.g., 1.2.3 → 1.2.4)
- **Minor updates** (e.g., 1.2.3 → 1.3.0) for stable versions
- **Production dependencies** primarily

### Conditions

- Automated merge is triggered via GitHub Actions workflow (`.github/workflows/dependabot-auto-merge.yml`)
- Pull request must pass all status checks before merging
- Using **squash merge** strategy for cleaner commit history

### Manual Review Required

- Major version updates (e.g., 1.x.x → 2.0.0)
- Development dependencies with breaking changes
- Dependencies affecting core functionality
- Any update with potential security or stability concerns

## Best Practices

### For Developers

1. **Respond promptly** to Dependabot PRs that require manual review
2. **Review changelog** for major version updates before merging
3. **Test locally** if a dependency update affects your work
4. **Monitor CI/CD** results to catch any regressions

### For Teams

1. **Use the weekly schedule** as a predictable update window
2. **Batch-review** Dependabot PRs during backlog refinement
3. **Document breaking changes** from major updates in PR comments
4. **Tag teammates** if an update requires domain expertise

## Monitoring and Maintenance

### GitHub Dashboard

- Visit the repository's Dependabot tab to see update history
- Check "Security alerts" for critical vulnerability announcements

### PR Review

- Dependabot PRs are marked with the `dependencies` label
- Reviewer: `tonoccho` (default)
- Filter PRs by `author:dependabot[bot]` to focus on dependency updates

### Troubleshooting

**Dependabot PRs failing CI**: Check the GitHub Actions logs to understand the failure:
- Test failures may indicate breaking changes
- Lock file conflicts may require resolution
- Security policy violations may require human review

**Auto-merge not triggering**: Verify that:
- Status checks are passing
- The PR author is `dependabot[bot]`
- GitHub Actions workflow has permission to merge

## Security Considerations

### Version Pinning

- We allow all dependency types and don't pin versions strictly
- This ensures timely security patches
- Regular updates are performed to manage risk

### Vulnerability Response

- Critical/high severity vulnerabilities trigger immediate PRs
- GitHub Security Advisories are monitored via the dependabot workflow
- Manual review and merging are done for high-severity updates

## Future Improvements

- Consider **automated testing** enhancements (e.g., integration tests for dependencies)
- Evaluate **grouping strategies** for related dependency updates
- Monitor **update failure rates** to identify problematic dependencies

---

**Last updated**: August 2026  
**Policy maintainer**: DevOps Team
