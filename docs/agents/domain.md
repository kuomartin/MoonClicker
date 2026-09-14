# Domain Docs

How the engineering skills should consume this repo's domain documentation when exploring the codebase.

## Before exploring, read these

- **`CONTEXT.md`** at the repo root, or
- **`CONTEXT-MAP.md`** at the repo root if it exists — it points at one `CONTEXT.md` per context. Read each one relevant to the topic.
- **`docs/adr/`** — read ADRs that touch the area you're about to work in. In multi-context repos, also check `src/<context>/docs/adr/` for context-scoped decisions.

If any of these files don't exist, **proceed silently**. Don't flag their absence; don't suggest creating them upfront. The `/domain-modeling` skill (reached via `/grill-with-docs` and `/improve-codebase-architecture`) creates them lazily when terms or decisions actually get resolved.

## File structure

Single-context repo (this repo):

```
/
├── CONTEXT.md
├── docs/adr/
│   ├── 0001-event-sourced-orders.md
│   └── 0002-postgres-for-write-model.md
└── app/
```

## Use the glossary's vocabulary

When your output names a domain concept (in an issue title, a refactor proposal, a hypothesis, a test name), use the term as defined in `CONTEXT.md`. Don't drift to synonyms the glossary explicitly avoids.

If the concept you need isn't in the glossary yet, that's a signal — either you're inventing language the project doesn't use (reconsider) or there's a real gap (note it for `/domain-modeling`).

## Cite ADRs by number and decision

When code, a comment, or a doc points at an ADR, name the ADR and its decision — `見 ADR-0012` — and write the reasoning itself at the call site. A citation that names a passage *inside* an ADR couples the code to that ADR's shape: the ADR can no longer be tightened without breaking the citation, so the reference holds the decision record hostage. Needing to write "ADR-0012 的兩段交接那一節" is the signal that the reason belongs in the comment you are writing.

When what you want is a **term** rather than a decision, cite `CONTEXT.md`. The glossary owns the vocabulary; an ADR only happens to use it.

ADR numbers are an append-only ledger. A removed ADR leaves its number empty — gaps are correct, and renumbering would break the commit messages that cite them.

## Flag ADR conflicts

If your output contradicts an existing ADR, surface it explicitly rather than silently overriding:

> _Contradicts ADR-0007 (event-sourced orders) — but worth reopening because…_
