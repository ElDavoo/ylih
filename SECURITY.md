# Security Policy

## Supported Versions

ylih is a single, actively developed app with no parallel release branches. Security fixes land on
`main` and ship in the next release to both F-Droid and Google Play. Older versions aren't
patched — update before reporting, in case it's already fixed.

## Reporting a Vulnerability

Report suspected vulnerabilities privately, not in a public issue:

- [GitHub Security Advisories](https://github.com/ElDavoo/ylih/security/advisories/new)
  for this repository.

Include what you found, how to reproduce it, and its impact. I'll acknowledge within a few days
and, once triaged, say whether it's accepted (with a fix timeline) or declined (with reasoning).

For context: the app has no internet permission and stores all data locally (see
[`PRIVACY.md`](PRIVACY.md)), so the threat model is local — data exposure to other apps on the
same device, not network or server-side attacks.
