## What this changes

<!-- One paragraph. What behaviour is different after this merges? -->

## Why

<!-- The problem. Link the issue or the audit finding. -->

## Definition of done

Tick only what is genuinely true. An unticked box with a reason is fine; a wrongly ticked one is not.

- [ ] Design documented (ADR if this is an architectural decision)
- [ ] Implementation complete
- [ ] Unit test
- [ ] Integration test, where the change crosses a boundary
- [ ] **Failure path tested** — not only the happy path
- [ ] Metric emitted, if this is runtime behaviour
- [ ] Docs updated
- [ ] Security implications considered
- [ ] CI green

## Security checklist

- [ ] No secret, key, token or credential added — including in tests and fixtures
- [ ] No control described as stronger than it is (the denylist is lint; Docker is not a microVM)
- [ ] Any new outbound call passes `SsrfGuard`
- [ ] Any new tool declares a side-effect class
- [ ] Any new claim in the README or `METRICS.md` cites code + test + artifact

## Evidence

<!--
Paste real output. "Tests pass" is not evidence; the surefire summary line is.
Never paste a number you did not observe.
-->

```
```
