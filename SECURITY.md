# Security Policy

## Supported versions

Security fixes are made to the latest released version. Please upgrade to the newest release before reporting an issue.

## Reporting a vulnerability

Please do not open a public issue for a security problem. Email **security@minebit.dev** with:

* a description of the problem and its impact
* the affected version
* steps or a small example that reproduces it

You will get a response within 3 days. If the report is confirmed, we will work with you on a fix and agree on when to publish it, and credit you in the release notes if you wish.

## Scope

Examples of what counts: SQL injection through BORM's own APIs, data being written to or read from the wrong rows, and credentials being logged. Table and column names are written into SQL, so BORM only accepts plain identifiers for them. Values are always passed as bound parameters.
