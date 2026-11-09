# Contributing

Thanks for helping out. Bug reports, fixes and small focused improvements are welcome.

## Before you start

* For anything larger than a fix, open an issue first so we can agree on the approach.
* Security problems go to security@minebit.dev, not the issue tracker. See [SECURITY.md](SECURITY.md).

## Building

You need Java 21 and Maven.

```bash
mvn clean verify
```

This must pass before a pull request is merged: all tests green and SpotBugs reporting zero findings at Medium or above. If a SpotBugs finding is a false positive, add a narrow exclusion with a reason to `spotbugs-exclude.xml` rather than silencing a whole category.

### MySQL tests

The tests run on SQLite by default. A set of MySQL tests only runs when you give it a server, which is worth doing for any change to SQL generation, key handling or ordering:

```bash
docker run -d --name borm-mysql-test -e MYSQL_ROOT_PASSWORD=borm-test -e MYSQL_DATABASE=borm -p 127.0.0.1:3307:3306 mysql:8.4
mvn clean verify -Dborm.mysql.port=3307
docker rm -f borm-mysql-test
```

## Code style

* Match the surrounding code. Braces on every `if`, early returns instead of nesting, and explicit imports.
* Keep changes focused. Please don't mix unrelated refactors into a fix.
* Bug fixes need a test that fails without the fix. New behavior needs tests for both the success path and the failure paths.
* Failures in background work must be logged and must complete their future, never silently disappear.

## Commits and pull requests

* Use short, descriptive commit messages, for example `fix: Complete table registration when a foreign row is missing`.
* Describe what changed and why in the pull request, and note anything you could not test (for example a database you don't have).
* Add an entry under `Unreleased` in [CHANGELOG.md](CHANGELOG.md) for any change users would notice.

## Releasing

Maintainers only. Set the version in `pom.xml` to the release number, move the `Unreleased` notes under that version in the CHANGELOG, then push a tag named exactly like the version (for example `2.0.0`). The Release workflow deploys to the Minebit repository and creates the GitHub release. A published version can never be reused.
