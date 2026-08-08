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
