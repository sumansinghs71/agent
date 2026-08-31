# Contributing

## Before you start

Read [docs/00_CURRENT_STATE_AUDIT.md](docs/00_CURRENT_STATE_AUDIT.md). It is the honest description
of what this system does and does not do, and it is kept current.

## Ground rules

1. **Never commit a secret.** `gitleaks` gates every push. Use `${ENV_VAR}` references and add the
   variable to `.env.example`.
2. **Do not describe a control as stronger than it is.** The denylist is lint, not a sandbox.
   Docker is containment, not a microVM. Language that overstates a control will be sent back.
3. **A security control needs a test that attacks it.** Asserting that a flag appears in an argv is
   necessary but not sufficient; add a test that tries the attack.
4. **No unmeasured numbers.** Performance and evaluation claims need a committed artifact showing
   how they were produced.

## Definition of done

A change is done when design, implementation, unit test, failure-path test, emitted metric, updated
docs, reviewed security implications, and passing CI are all present — not when it compiles.

## Running the build

```bash
cp .env.example .env      # fill in values; .env is git-ignored
./mvnw clean verify       # compile, unit + context tests, coverage report
```

The sandbox adversarial suite needs a running Docker daemon and skips without one:

```bash
docker pull python:3.11-slim
./mvnw test -Dtest=DockerSandboxAdversarialTest
```

A skipped security test is not a passing security test. CI asserts that the suite actually ran.
