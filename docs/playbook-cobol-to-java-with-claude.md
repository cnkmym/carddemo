# Playbook: COBOL → Java Modernization with Claude

A reusable, copy-paste workflow distilled from the [CardDemo case
study](./case-study-claude-cobol-modernization.md). Use this when
starting a fresh COBOL-to-Java migration project. Each phase has a
ready-to-paste prompt, expected output, and common pitfalls.

> **Customize before pasting:** every prompt has `{ALL_CAPS_PLACEHOLDERS}`
> — replace these with your project's specifics before sending.

---

## Quick-start

| Phase | Time   | Output                                       | Skip if…                          |
| ----- | ------ | -------------------------------------------- | --------------------------------- |
| 0     | 5 min  | `CLAUDE.md`, project bootstrap               | already have CLAUDE.md            |
| 1     | 30 min | `docs/{overview,architecture,directory-guide}` | repo already has a developer map |
| 2     | 45 min | `docs/cobol/*.md` per-program reference      | < 5 COBOL programs total          |
| 3     | 5 min  | Decision: which program to migrate first     | program already chosen            |
| 4     | 20 min | `docs/migration/{program}-to-java-plan.md`   | never                             |
| 5     | 60 min | Java port + COBOL oracle + tests             | never                             |
| 6     | 20 min | Tests passing, byte-for-byte verified        | never                             |
| 7     | 10 min | Architecture diagram + memory entries        | never                             |
| 8     | varies | Each subsequent program (foundation reused)  | this is the first migration       |

**Total for first program: ~3 hours.** Subsequent programs: ~1 hour
each, because Phases 1, 2, and most of 5 are already done.

---

## Before you start — checklist

- [ ] **Claude Code installed** in the repo. `cd` into it, then `claude`.
- [ ] **Tooling on the host** — for verification later: `java 17+`,
      `mvn`, `gnucobol` (`apt install gnucobol` / `brew install gnu-cobol`).
- [ ] **Git initialized** so changes are reversible (`git init` if needed).
- [ ] **Sample data available** — at minimum one realistic input fixture
      per file the COBOL reads. ASCII or EBCDIC are both fine; if EBCDIC,
      flag this in Phase 1 so Claude builds a converter early.
- [ ] **Read** the [silent killers list](#common-pitfalls-the-silent-killers)
      at the bottom of this doc before starting Phase 4.

---

## Phase 0 — Initialize the project

**Goal:** Tell Claude about the codebase shape and your target so every
later prompt has correct context.

**Prompt:**

```
I'm starting a COBOL→Java modernization project on this repo.

Codebase shape (please verify by exploring; correct me if anything is wrong):
- COBOL source: {COBOL_SOURCE_PATH, e.g. app/cbl/}
- Copybooks:    {COPYBOOK_PATH, e.g. app/cpy/}
- JCL:          {JCL_PATH, e.g. app/jcl/}
- Sample data:  {DATA_PATH, e.g. app/data/ASCII/}
- Encoding of sample data: {ASCII | EBCDIC | unknown}

Target Java location: {JAVA_OUTPUT_PATH, e.g. app/java/migration/}

Please:
1. Run /init to create a CLAUDE.md grounded in what's actually here.
2. Add a section to CLAUDE.md noting this is an active migration
   project, the target Java location, and that byte-for-byte
   equivalence with the COBOL source is the contract for every
   migrated program.
3. Confirm or correct the paths above based on what you actually find.
```

**Expected output:** A CLAUDE.md at repo root. Claude confirms or
corrects the paths.

**Pitfalls:** If the repo doesn't have ASCII fixtures (only EBCDIC binary
VSAM dumps), tell Claude up front — the byte-for-byte test harness needs
either ASCII fixtures or an EBCDIC→ASCII converter, and that's a Phase 5
deliverable in that case.

---

## Phase 1 — Codebase discovery (high-level docs)

**Goal:** A developer reading `docs/` can understand the project in 15
minutes without diving into source.

**Prompt:**

```
Please create developer documentation under docs/ that helps a new engineer
understand this codebase. Specifically:

- docs/overview.md — what the application is, who it's for, the tech stack.
- docs/architecture.md — execution paths (online vs batch), how components
  fit together, where data lives, build/deploy mechanics.
- docs/directory-guide.md — one paragraph per directory in the repo,
  including file types and 2–3 representative filenames.

Use Explore subagents in parallel for the directory walk. Read the root
README.md and any module-level READMEs as starting points but verify
everything against the actual files — flag any discrepancies between
existing docs and the source.

Write nothing speculative; ground every claim in a file you actually read.
```

**Expected output:** 3 files in `docs/`. Each ~100–250 lines. Cross-link
each other.

**Pitfalls:** If the repo is small (< 20 files), this is overkill — a
single docs/README.md suffices. Use judgment.

---

## Phase 2 — Per-program reference (parallel deep-dive)

**Goal:** Lookup-style reference for every COBOL program. When you later
ask "what does PROGRAM_X do?", the answer is one `grep` away.

**Prompt:**

```
Please produce per-program reference documentation under docs/cobol/.

Group programs into 3–5 functional slices (e.g. online vs batch vs each
extension module). Spawn ONE subagent per slice IN PARALLEL — use the
Agent tool with a custom subagent type if you have a cobol-to-java
specialist, otherwise general-purpose.

Each slice's doc should include, per program:
- Purpose (one sentence)
- Driving JCL / CICS transaction
- Key copybooks used
- Files / databases / queues touched
- Programs called (CALL, EXEC CICS LINK, EXEC CICS XCTL)
- API surface (EXEC SQL / EXEC CICS / CALL 'CBLTDLI' / MQ verbs)
- Major paragraphs (3–8 bullets, control flow)
- Notable patterns (REDEFINES, OCCURS, COMP-3, etc.)

Write each agent's output to docs/cobol/{slice-name}.md. After all
agents finish, write docs/cobol/README.md as an index.

When source disagrees with module READMEs, follow the source and FLAG
the discrepancy.
```

**Expected output:** `docs/cobol/` with one index + N slice docs.
Estimate ~500–1,500 lines per slice for medium programs.

**Pitfalls:**
- Don't spawn one agent per program — overhead dominates. 3–5 agents
  total, each scoped to ~10 programs, is the sweet spot.
- Subagent context burns fast on large programs. If a program is > 2K
  lines, tell the agent to sample 3 representative paragraphs rather
  than reading the whole thing.

---

## Phase 3 — Pick the first migration target

**Goal:** Choose a program that establishes reusable foundation
utilities for all subsequent migrations.

**Prompt:**

```
Based on docs/cobol/, recommend ONE program to migrate first. Optimize
for foundation value, not business priority. Good first-migration
candidates have these properties:

- Pure batch (no CICS, no MQ, no IMS) — these have simpler equivalence
  oracles.
- Touches multiple file types (VSAM input, sequential output, REWRITE)
  so the I/O codec is exercised broadly.
- Uses signed decimal arithmetic (COMP-3 or zoned decimal with V99) so
  we have to get BigDecimal semantics right.
- Has good driving JCL and existing test fixtures.
- Is < 1,000 lines so the first migration ships in one session.

Avoid for the first migration: anything online (CICS/BMS), anything
multi-database (DB2+IMS+MQ), anything with elaborate ABEND/recovery
logic.

Give me your top 3 candidates ranked, with one-paragraph rationale each.
```

**Expected output:** 3 ranked candidates with rationale. You pick one.

**Pitfalls:** Don't pick a critical-path program first. Picking a
secondary "interest calculator" or "report writer" lets you fail fast
without business risk.

---

## Phase 4 — Plan the migration (plan mode)

**Goal:** A complete, approval-gated migration plan that surfaces every
silent killer BEFORE any code is written.

**Prompt:**

```
We're migrating {PROGRAM_NAME} (e.g. CBACT04C) to Java. The Java code
lives at {JAVA_OUTPUT_PATH}/{module-name}/.

Please plan this migration. Use plan mode. Read:
- {PROGRAM_NAME} source in full
- Every copybook it COPY-s
- The driving JCL
- A representative sample of the input fixtures (first ~5 records of each
  file at byte-level — use awk to print byte positions, do NOT trust the
  upstream README about field layouts)

In the plan, include:

1. Source contract — every input file (org, record layout, key), every
   output file (incl. REWRITE semantics), the algorithm at paragraph
   level, all numeric formats with explicit byte-width and scale.

2. Equivalence requirements — exactly which output artifacts must match
   byte-for-byte. Identify sources of non-determinism (timestamps,
   random IDs) and how to make them injectable.

3. Target Java architecture — package layout, build system (Maven, Java
   17, JUnit 5 + AssertJ unless I say otherwise), domain records that
   wrap raw bytes (so FILLER passes through unchanged), I/O classes
   that mirror VSAM/sequential semantics.

4. Step-by-step migration steps as discrete commits.

5. Logic equivalency safeguards — call out each silent killer:
   - Sign overpunch encoding ({}A–IJ–R for zoned decimal)
   - COBOL COMPUTE without ROUNDED → BigDecimal RoundingMode.DOWN
   - Timestamp injection
   - File-status semantics (especially '23' = not found and any fallback)
   - Encoding (use ISO-8859-1 byte-identity I/O, not UTF-8)

6. Test strategy — unit tests + a portable GnuCOBOL oracle build of the
   original program (sequential files only; in-memory tables for
   random-access lookups) + a golden-file IT that diffs Java vs oracle.

7. Decisions to confirm with me before implementation (Java version,
   build system, package name, oracle approach).

Write the plan to docs/migration/{PROGRAM_NAME}-to-java-plan.md, then
ExitPlanMode.
```

**Expected output:** A 200–400 line plan file. Claude calls
`AskUserQuestion` for any genuinely ambiguous design choices, then
`ExitPlanMode` for your approval.

**Pitfalls:**
- If Claude skips reading the actual fixture bytes (e.g. relies on
  copybook field widths only), the plan will miss data-format quirks.
  Insist on byte-level inspection of inputs.
- Approve the plan with "LGTM" or override specific points. Vague
  responses like "looks fine" lead to rework.

---

## Phase 5 — Implement (no test execution yet)

**Goal:** Working Java port + COBOL oracle + complete test suite, all
written but not run.

**Prompt:**

```
Plan approved — please implement it.

Constraints:
1. Do NOT run mvn test yet — the test environment may not be ready.
   Compile-check only (javac is fine).
2. Write the COBOL portable port (CBACT04P-style) under
   {JAVA_OUTPUT_PATH}/{module}/cobol-reference/ with build.sh, run.sh,
   regen-golden.sh.
3. Write a HOW-TO-TEST.md alongside README.md so I can run the tests
   myself when ready.
4. Use immutable record-classes that wrap raw byte buffers — the
   FILLER and any unread fields must pass through unchanged.
5. ZonedDecimalCodec: round-trip every overpunch char ({}A–IJ–R0–9).
6. BigDecimal arithmetic: RoundingMode.DOWN to match COBOL truncation.
7. ISO-8859-1 encoding everywhere — never UTF-8 on the I/O path.
8. Db2Timestamp (or equivalent) must accept an injectable Clock so
   tests can fix the instant. ALSO expose a `--ts=YYYY-MM-DD-HH.MM.SS.MMUUUU`
   CLI argument on the main entry point so command-line runs are
   byte-reproducible (not just IT runs).
9. The COBOL oracle build script (cobol-reference/tools/build.sh)
   MUST include `cobc -fsign=EBCDIC` — without it, GnuCOBOL writes
   positive-zero signed fields as plain digits instead of mainframe-
   style overpunches, breaking byte-for-byte equivalence.
10. The COBOL oracle run script (cobol-reference/tools/run.sh) MUST
    export `COB_LS_FIXED=Y` before invoking the binary — without it,
    GnuCOBOL LINE SEQUENTIAL output strips trailing FILLER spaces,
    so 350-byte fixed-length records become variable-length.
11. ABEND-equivalent exit in the COBOL oracle: use `STOP RUN RETURNING N`
    (NOT `CALL "CEE3ABD"` — that's mainframe-only — and NOT `CALL
    "CBL_EXIT_PROC"` — different semantics, takes 2 parameters).

Work step-by-step from the plan. Mark each step done as you complete it.
End with:
- A summary of what was built
- Confirmation that javac compiled all sources clean
- Pointer to HOW-TO-TEST.md for me to run the tests myself
```

**Expected output:** Implementation is complete. Final message tells you
what to run next.

**Pitfalls:**
- If your environment has GnuCOBOL and Maven set up, you can drop the
  "do NOT run mvn test" constraint — Claude will run them and triage
  failures in-loop, saving you a round-trip.
- Watch for Claude over-engineering. If you see a `Factory` or
  `Strategy` pattern in a 200-line program, push back.

---

## Phase 6 — Verify (run tests, fix fixture bugs)

**Goal:** All unit tests pass; the byte-for-byte IT passes against the
COBOL oracle.

**Step 6a — run unit tests yourself:**

```
mvn test
```

If failures, paste the output and use this prompt:

```
mvn test failed. See output below.

{PASTE FULL OUTPUT}

Please triage every failure to a root cause and fix. Important
heuristic: in a byte-for-byte port, bugs concentrate in test fixtures
(off-by-one digit padding, wrong overpunch char, miscounted field width)
NOT in production code. Verify each failure against the actual byte
positions of the fields involved before patching production code.
```

**Step 6b — generate golden files and run the IT:**

```
bash {JAVA_OUTPUT_PATH}/{module}/cobol-reference/tools/regen-golden.sh
mvn verify
```

If the IT shows a byte-for-byte diff:

```
GoldenFileEquivalenceIT failed at byte offset {N}. See snippet from the
test output below.

{PASTE SNIPPET INCLUDING "Expected length=X, actual length=Y"}

Please identify which field of which record this offset corresponds to,
then triage. Common causes in priority order:

If file lengths differ (length=X vs length=Y):
1. GnuCOBOL stripped trailing spaces — verify run.sh exports COB_LS_FIXED=Y.
2. Java omitted the trailing \n on output records.
3. One side wrote N records, the other wrote N±1 (e.g. EOF flush bug).

If file lengths match but bytes differ at offset N:
1. GnuCOBOL omitted the EBCDIC overpunch on positive-zero signed
   fields — verify build.sh passes -fsign=EBCDIC. (Symptom: actual
   has '{' at position N, expected has '0'.)
2. Sign-overpunch encode regression on the Java side — codec wrote
   '0' where it should have written '{' (or vice versa).
3. RoundingMode.HALF_UP slipped in instead of DOWN.
4. Timestamp not actually injected (real CURRENT-DATE leaked through
   on either the COBOL side, where TEST_FIXED_TS env var must be set,
   or the Java side, where useFixedClock must be called in @BeforeAll).
5. Account REWRITE order changed (HashMap vs LinkedHashMap regression).
6. CLI run instead of IT run on the Java side (CLI doesn't inject the
   fixed clock unless --ts= flag was added per Phase 5 instructions).
```

**Expected output:** All 49+ tests pass. `BUILD SUCCESS`.

**Pitfalls:**
- Don't let Claude "fix" production code in response to a fixture bug.
  If the production code is correct and a test expectation is wrong,
  fix the expectation.
- The IT auto-skips when golden files are absent. If you see "Tests
  run: 1, Skipped: 1" for the IT, you forgot Step 6b.

---

## Phase 7 — Document and memorize

**Goal:** The next migration is cheaper because lessons from this one
are captured.

**Prompt:**

```
The migration of {PROGRAM_NAME} is complete and verified. Please:

1. Generate a Mermaid class-dependency diagram for the Java port and
   save it to docs/migration/{PROGRAM_NAME}-java-architecture.md.

2. Save the following to your memory system as project memories so they
   apply to subsequent migrations in this codebase:
   - The sign-overpunch encoding convention found in our ASCII fixtures
   - Any field-layout quirks discovered (e.g. "discgrp INT_RATE is
     actually 15.00% not 1.50% — readme was wrong")
   - The BigDecimal rounding rule (DOWN, not HALF_UP)
   - The timestamp-injection mechanism
   - The package layout / Maven setup we settled on
   - The COBOL oracle build approach (LINE SEQUENTIAL + in-memory tables)

3. Append a one-line pointer in docs/cobol/{slice}.md (in the
   {PROGRAM_NAME} section) linking to the Java port and the migration
   plan.
```

**Expected output:** Architecture diagram, memory entries, doc cross-links.

---

## Phase 8 — Subsequent migrations (the cheap path)

**Goal:** Migrate program N+1 in ~1 hour by reusing the foundation.

**Prompt:**

```
We're migrating {NEXT_PROGRAM} (e.g. CBTRN02C) to Java. This is the
{N}-th migration in this codebase; we already have:

- Reusable utilities in {JAVA_OUTPUT_PATH}/{first-module}/src/main/java/
  (ZonedDecimalCodec, FixedWidthFormat, Db2Timestamp, BatchAbendException)
- A working COBOL oracle pattern under cobol-reference/
- Verified migration of {FIRST_PROGRAM}

Please:

1. Decide whether to extract the foundation utilities into a shared
   module (do this once we have ≥ 2 migrations consuming them — they
   should already exist in {first-module}, so just reference / refactor).

2. Plan + implement {NEXT_PROGRAM} following the same pattern. The
   foundation utilities are reused as-is; only the new program's
   domain records, file readers/writers, and main orchestrator need
   to be written fresh.

3. Skip the codebase-discovery phases (1–2) — docs/cobol/ already
   covers {NEXT_PROGRAM}. Use that doc as your starting point for the
   plan.

4. Use plan mode for the plan; ExitPlanMode for my approval; then
   implement; then I'll run tests and report.

If the new program uses constructs we haven't seen before (e.g. DB2,
IMS, MQ, GDG, sort verbs), call them out in the plan as new
foundation work. Otherwise straight reuse.
```

**Expected output:** New module beside the first one, sharing utility
classes via package import or shared Maven module.

**Pitfalls:**
- After 2–3 migrations, **do** extract shared utilities into a sibling
  Maven module so they're not buried in one program's code. The right
  time to do this is when you have 2 callers, not when you have 1.

---

## Common pitfalls (the silent killers)

These are the things that produce a "looks-right but is wrong" Java
port. Burn them into your eyeballs before Phase 4:

| # | Killer                                                                                          | Fix                                                                                                                       |
| - | ----------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------- |
| 1 | EBCDIC zoned-decimal sign overpunches in ASCII fixtures (`{}A–IJ–R`)                            | Round-trip-tested codec; ISO-8859-1 byte-identity I/O                                                                     |
| 2 | COBOL `COMPUTE` without `ROUNDED` truncates toward zero                                         | `BigDecimal.divide(divisor, scale, RoundingMode.DOWN)` — never `HALF_UP`                                                  |
| 3 | `FUNCTION CURRENT-DATE` produces non-deterministic timestamps                                   | Inject a fixed `Clock` in tests; add a `TEST_FIXED_TS` env var to the COBOL oracle                                         |
| 4 | Module READMEs are out of date (queue names, message layouts, field positions)                  | Trust source code, not READMEs. Verify with `awk` on actual byte positions                                                |
| 5 | LinkedHashMap vs HashMap silently changes record order in REWRITE-equivalent flush              | Use a separate `insertionOrder` list; iterate that list when flushing                                                      |
| 6 | Trailing-newline mismatch between mainframe RECFM=F and GnuCOBOL LINE SEQUENTIAL                | Document the divergence in HOW-TO-TEST.md; the oracle proves Java≡portable-COBOL, not Java≡mainframe-COBOL                |
| 7 | DB2 `DECIMAL(p,s)` host-variable layouts differ from VSAM zoned-decimal in same-named fields    | Read the DCLGEN, not just the copybook. The plan must call out which is canonical                                          |
| 8 | `OPEN I-O` REWRITE preserves record order on real VSAM but in-memory ports may reorder          | LinkedHashMap or explicit insertion-order list                                                                            |
| 9 | File status `'23'` (not found) is recoverable in COBOL but a thrown exception is fatal in Java  | Use `Optional` for lookups that can legitimately miss; reserve exceptions for ABEND-equivalent conditions                  |
| 10 | Test fixtures: off-by-one digit padding makes `S9(09)V99` decode as `S9(11)V99` and pass anyway | Add length-validation in your fixture-writing helper that throws on mismatch                                              |
| 11 | GnuCOBOL writes positive-zero signed fields as plain `0` instead of `{` overpunch              | `cobc -fsign=EBCDIC` in build.sh. Required for byte-for-byte vs Java; not the GnuCOBOL default                            |
| 12 | GnuCOBOL `LINE SEQUENTIAL` strips trailing FILLER spaces on output                              | Export `COB_LS_FIXED=Y` in run.sh before invoking the binary. Required even when the FD declares a fixed record length    |
| 13 | `CALL "CEE3ABD"` is mainframe-LE-only; GnuCOBOL's `CBL_EXIT_PROC` has different semantics       | In the portable oracle, just use `STOP RUN RETURNING N` for ABEND. Cleaner, portable, no parameter-mismatch surprises     |
| 14 | CLI runs of the Java port use the wall clock; the IT injects a fixed clock                      | Expose a `--ts=` CLI argument that calls `Db2Timestamp.useFixedClock(...)` so manual runs are byte-reproducible           |

---

## Context-window and memory tactics

Five techniques that kept the case-study migration tractable:

1. **Subagents for breadth** — 3–5 parallel agents in Phase 2; one call
   per slice. Their final summaries land in main context, not their
   tool-call history.
2. **Persistent docs** — every artifact saved to `docs/` or alongside
   code, so future sessions can re-read instead of re-derive.
3. **Plan-mode separation** — the plan is a self-contained document.
   Implementation can resume after a context reset using just the plan.
4. **File-based memory** — save project facts (sign overpunch conventions,
   field-layout quirks, package layout decisions) to Claude's memory
   system in Phase 7. They apply across all future sessions on this
   codebase.
5. **Targeted re-reads** — when a test fails, read just the failing
   test file and the relevant fixture bytes, not the whole production
   tree.

---

## Adapting this template to other source languages

Most of this playbook generalizes. Substitute:

| COBOL specific                  | Generalize to                                                  |
| ------------------------------- | -------------------------------------------------------------- |
| GnuCOBOL oracle                 | A portable interpreter / runtime of the source language        |
| EBCDIC zoned decimal            | Whatever fixed-width binary format the source uses             |
| Copybooks                       | Header files / shared schemas                                  |
| `EXEC SQL` / `EXEC CICS`        | The transaction / DB framework calls of the source language    |
| `CALL 'CEE3ABD'`                | Whatever ABEND/exit primitive the source uses                  |
| ASCII fixture files             | Whatever input data format you can produce deterministically   |

The structure — discovery → per-program reference → plan → implement →
oracle-based equivalence test → memorize — is language-agnostic.

---

## Where to keep this playbook

For cross-project reuse, copy this file to your home directory or a
shared templates location:

```bash
# Personal copy
cp docs/playbook-cobol-to-java-with-claude.md ~/playbooks/

# Or per-organization shared location
cp docs/playbook-cobol-to-java-with-claude.md /shared/eng-templates/
```

Then in any new modernization project, paste the contents into a
`docs/playbook.md` of that repo, customize the placeholders for that
project's paths and language, and start at Phase 0.
