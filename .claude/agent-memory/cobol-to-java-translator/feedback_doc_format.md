---
name: CardDemo doc format conventions
description: How to write per-module deep-dive documentation in this codebase
type: feedback
---

Per-module COBOL deep-dive docs go under `docs/cobol/` and follow this style: markdown headings only (no emojis), backticks for DLI function codes, segment names, SQL keywords, table names, queue names, transaction codes, file paths, and paragraph names. Use repo-relative paths like `app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl`. Quote short COBOL snippets when they illustrate IMS/DB2/MQ patterns. Aim ~700-1100 lines per module deep dive (auth-IMS-DB2-MQ ran ~1180 because it's the largest).

**Why:** User explicitly specified format constraints in the doc-writing request, including "Do not duplicate `docs/architecture.md`. Focus on per-program detail." General docs (overview/architecture/directory-guide) already exist and only briefly mention each extension; the cobol/*.md files are where the detail belongs.

**How to apply:** When asked to add/extend per-module documentation, follow this layout: module overview (~20 lines) → IMS/DB2/MQ schemas → CICS resources → one section per COBOL program (purpose, online/batch, PSB, copybooks, DL/I, SQL, MQ, CICS, control flow, notable patterns) → batch jobs → cross-module wiring. Verify everything from actual source — don't infer from filenames.
