# AI contribution policy

Studio Mobile allows AI-assisted contributions because they genuinely help small teams ship
carefully reviewed work. It does not allow unreviewed code, invented claims, or AI-generated
"evidence". This page is the full policy so nobody has to guess.

## The rule in one sentence

**Anyone may write the code — a human must review it, run it, understand it, and describe it
honestly, and that human is the accountable author of the pull request.**

## Allowed

- Drafting code, tests, docs, refactors, translations with AI tools.
- Using AI to explore options, review your own diff, or write a first version of a test matrix.
- Agentic tools that run builds/tests — provided the human checks the results themselves.
- Committing AI-written code that you have read line by line and that passes the same review bar as
  human-written code.

## Not allowed

- Pull requests that a human cannot explain in detail.
- "Verified" claims, screenshots or QA reports for runs that did not actually happen.
- AI-generated documents that describe features which do not exist in this repository.
- Unreviewed bulk refactors ("rewrote the whole app with an agent") — split into reviewable changes.
- Silently swallowed exceptions, stub implementations presented as features, or tests that assert
  nothing.
- Copying code from sources with incompatible licenses, even if an AI produced it. You are
  responsible for license hygiene.

## Disclosure requirements

In every PR description, include:

```markdown
## AI assistance
- Tool(s):
- Used for (code / tests / docs / exploration):
- Human review performed: (what you read, ran, and corrected)
- Anything the tool got wrong that you fixed:
```

If you used no AI, write `none`. That is equally fine.

## Reviewer checklist for AI-assisted PRs

1. Could the author explain the diff if asked? Spot-check by asking a question about a non-obvious
   line.
2. Are comments/docs factual? (AI models happily invent Roblox APIs and endpoints — verify against
   official documentation, and cite where a claim came from.)
3. Do tests test the change? Fail the build on the old code and see them fail.
4. Any invented function/type/constant that does not exist in the codebase?
5. Any hidden network calls, telemetry, or credential handling changes? These are rejected unless
   explicitly discussed in an issue first.
6. License provenance for anything visibly copied.

## Accountability

The GitHub account that opens the PR owns the change: bugs, regressions and honesty of the
description. "An AI wrote it" is not a defense in review, in issues, or in release notes.

## Enforcement ladder

- **First time:** request changes with pointers to this document.
- **Repeated:** PR closed; the author is asked to submit smaller changes with real evidence.
- **Deliberate fabrication** (fake QA passes, fake screenshots, fake cloud success): permanent ban
  from contributing to this repository.

This policy exists to keep two things true at once: the project moves fast with AI tooling, and a
human is always responsible for what ships.
