# Security Policy

## Supported Versions

ylih is a single, actively developed app with no parallel release branches. Security fixes are
made against the latest version on `main` and shipped in the next release to both F-Droid and
Google Play. Older versions are not patched — please update to the latest release before
reporting an issue, in case it's already fixed.

## Reporting a Vulnerability

Please report suspected vulnerabilities privately rather than in a public issue:

- [GitHub Security Advisories](https://github.com/ElDavoo/ylih/security/advisories/new)
  for this repository.

Include what you found, how to reproduce it, and its potential impact. I'll acknowledge reports
within a few days and let you know whether it's accepted (with an expected fix timeline) or
declined (with the reasoning) once triaged.

For context: the app requests no internet permission and stores all data locally on-device (see
[`PRIVACY.md`](PRIVACY.md)), so the relevant threat model is local — things like data exposure to
other apps on the same device, not network attacks or server-side issues.
