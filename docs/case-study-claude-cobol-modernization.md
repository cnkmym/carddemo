# Case Study: Using Claude to Modernize a Large COBOL Codebase to Java

A concrete report from the [CardDemo](../README.md) modernization
exercise. The narrative uses one program (`app/cbl/CBACT04C.cbl`, the
nightly interest calculator) as the worked example, but the workflow,
patterns, and pitfalls generalize to any large mainframe-to-Java
migration.

---

## TL;DR

Claude can drive an end-to-end COBOL→Java migration of a single program
to **byte-for-byte equivalence**, including:

- Whole-codebase discovery and developer documentation (parallel agents).
- Per-program reference docs grounded in actual source.
- A migration plan negotiated with the user, gated on approval.
- Idiomatic Java port (Maven, JUnit, BigDecimal, immutable records).
- A portable GnuCOBOL "oracle" build of the original program for
  byte-for-byte verification.
- A test suite that proves equivalence on both synthetic and real fixtures.
- Iterative bug triage when tests fail.

The single-program migration in this case study took one working session,
produced **49 unit tests + 1 byte-for-byte equivalence integration test**
(all passing), and uncovered concrete bugs in the existing upstream
documentation along the way. The byte-for-byte verification required an
additional triage round of its own — see Phase 4b below — to resolve
GnuCOBOL's mainframe-incompatible defaults.

---

## What we did, in order

### Phase 1 — Discovery (~30 min)

1. **High-level codebase docs** in `docs/`: `overview.md`,
   `architecture.md`, `directory-guide.md`. Built by reading the root
   `README.md` and walking the directory tree.
2. **Per-program COBOL reference** in `docs/cobol/`: 5 documents
   covering all 44 COBOL programs (online, batch, three extension
   modules). Built by **5 parallel `cobol-to-java-translator` agents**,
   each scoped to one functional slice.

Output: ~3,800 lines of structured docs across 6 files. Discovery time
was dominated by parallel agent execution, not sequential reading.

### Phase 2 — Migration plan (~15 min, with one approval gate)

3. **Plan-mode design** for `CBACT04C` → Java. Used Claude's plan mode
   to read the COBOL, sample copybooks, and the actual ASCII fixtures.
   Surfaced design decisions to the user via `AskUserQuestion`
   (Java version, build system, test approach), then wrote the plan to
   `docs/migration/CBACT04C-to-java-plan.md`. Exited plan mode for user
   approval.

User said "LGTM" — implementation could proceed.

### Phase 3 — Implementation (~45 min)

4. **Java port** at `app/java/batch_processing_workflow/`: 14 production
   classes across `domain/`, `io/`, `util/` packages, plus a Maven
   `pom.xml`, `README.md`, and `HOW-TO-TEST.md`.
5. **Portable COBOL oracle** (`cobol-reference/CBACT04P.cbl`): same
   business logic as the original, but uses LINE SEQUENTIAL files and
   in-memory tables so it runs under stock GnuCOBOL with no Berkeley DB
   dependency. Plus three shell scripts (`build.sh`, `run.sh`,
   `regen-golden.sh`).
6. **Test suite** (8 classes, 49 tests): codec round-trips, decimal
   arithmetic, timestamp formatting, disclosure-group fallback,
   end-to-end scenarios, and a golden-file integration test that
   auto-skips when the COBOL oracle hasn't been built.

### Phase 4 — Unit-test triage (~20 min)

7. User ran `mvn test`; 6 failures across 3 test classes.
8. Triaged each failure to a root cause — every one was in the
   **synthetic test fixtures** (off-by-one digit padding, miscounted
   field widths, one wrong expected value carried over from the plan).
   None in production code.
9. Iterated fixes; all 49 unit tests passed on the second run.

### Phase 4b — Byte-for-byte verification triage (~25 min)

Unit tests passing ≠ byte-for-byte equivalence proven. With GnuCOBOL
installed, the user ran `bash cobol-reference/tools/regen-golden.sh`
followed by `mvn verify`. The integration test failed three times in
succession, each time pointing to a different GnuCOBOL config quirk:

10. **First failure: COBOL build error.** `CBL_EXIT_PROC` (the
    GnuCOBOL alternative to mainframe `CEE3ABD`) takes 2 parameters,
    not 1. Replaced with `STOP RUN RETURNING 999` — simpler and
    portable across COBOL implementations.
11. **Second failure: file-size mismatch (16,550 vs 17,550 bytes).**
    GnuCOBOL `LINE SEQUENTIAL` output strips trailing spaces by
    default, so each 350-byte transaction record was being written
    as 330 bytes (the 20-byte trailing FILLER stripped) plus `\n`.
    Fix: export `COB_LS_FIXED=Y` runtime env var in `run.sh` to
    preserve trailing spaces.
12. **Third failure: byte 142 mismatch in AMT field.** GnuCOBOL by
    default writes positive-zero signed numerics as plain `0` digits,
    not with the EBCDIC sign-overpunch (`{`) that real mainframe
    COBOL produces. Fix: add `cobc -fsign=EBCDIC` flag in `build.sh`.
13. After all three fixes, `mvn verify` reported **BUILD SUCCESS** and
    `Files.mismatch(expected, actual) == -1L` for both output files.

The pattern: each iteration's failure message pointed at a specific
byte offset, which mapped cleanly to a known field via the
`TransactionRecord` offset constants. No production code changed.

### Phase 5 — Architecture diagram (~5 min)

14. Mermaid class-dependency graph saved to
    `docs/migration/CBACT04C-java-architecture.md`, sitting next to the
    plan.

---

## How we handled context-window and memory limits

The CardDemo repo has ~31 COBOL programs in the core, ~30 copybooks, 38
JCL jobs, plus three extension modules — far too much to fit in one
context window. Five techniques kept the main conversation tight:

- **Subagents for breadth.** Phase 1's 5 parallel
  `cobol-to-java-translator` agents read ~30 COBOL programs across the
  three extension modules in one wall-clock interval. Each agent's
  context burned through hundreds of tool calls; the main conversation
  only saw their final summaries and the files they wrote. Without this,
  the discovery phase would have exhausted context before reaching the
  migration plan.
- **Persistent docs as context.** Every artifact landed under `docs/` or
  next to the code (`HOW-TO-TEST.md`, `cobol-reference/README.md`).
  These are re-readable by future sessions without re-deriving them.
- **Plan-mode separation.** The migration plan is a self-contained
  document. The implementation phase did not need to re-derive the plan
  — it just executed it. If implementation had been interrupted, a fresh
  session could have resumed from the plan.
- **Memory system.** Project-level facts saved to the file-based memory
  (`MEMORY.md` index plus per-fact files) — e.g. CBACT04C's sign
  overpunch convention, the AccountFile DEFAULT-fallback semantics —
  survive across conversations.
- **Targeted re-reads.** When the test failures came in, we did **not**
  re-read all 14 production classes. We read just the failing test files
  and the relevant fixture bytes, traced each error to a specific
  off-by-one, and patched in place.

---

## Critical discoveries (the silent killers)

These are the things that would have produced a "looks-right but is
wrong" Java port without Claude actively looking for them:

1. **EBCDIC zoned-decimal sign overpunches in the ASCII fixture
   files.** Files like `app/data/ASCII/acctdata.txt` preserve the EBCDIC
   convention `{}A–IJ–R` on the trailing digit of signed numerics. An
   ASCII-naive parser would fail on the first record. Surfaced from
   Claude's actual reading of the byte stream, not from the upstream
   README.
2. **`COMPUTE` truncation vs. rounding.** COBOL `COMPUTE` without
   `ROUNDED` truncates toward zero; the Java equivalent is
   `BigDecimal.divide(..., 2, RoundingMode.DOWN)`, NOT `HALF_UP`. A
   wrong rounding mode would silently diverge on edge cases.
3. **A real test oracle is non-negotiable.** Reading the COBOL and
   reasoning about behavior is not enough. The portable GnuCOBOL build
   exists so byte-level diffs catch regressions that humans wouldn't
   notice (e.g. the `TRAN-AMT` field on a $0.01 interest charge).
4. **Existing module READMEs are out of date.** The Phase 1 agents found
   that `app/app-vsam-mq/README.md` documents queue names and message
   layouts that don't match the COBOL source. Claude's docs follow the
   source and explicitly flag the discrepancy.
5. **Bugs concentrate in fixtures, not logic.** Of the 6 test failures
   in Phase 4, 0 were in production code. 6 were in synthetic test
   fixtures (string concatenations with off-by-one digit counts). The
   production code had been written carefully; the test data hadn't.
6. **GnuCOBOL ≠ mainframe COBOL by default.** Without
   `cobc -fsign=EBCDIC`, GnuCOBOL writes positive-zero signed
   numerics as plain `0` instead of `{` (the mainframe overpunch).
   Without `COB_LS_FIXED=Y`, `LINE SEQUENTIAL` output strips trailing
   spaces, breaking fixed-length record equivalence. Both flags are
   essentially mandatory for byte-for-byte testing, and both are
   discovered only by running the IT and seeing it fail.
7. **`CEE3ABD` has no drop-in GnuCOBOL equivalent.** The closest
   built-in (`CBL_EXIT_PROC`) has different semantics and signature.
   Use `STOP RUN RETURNING <code>` for the portable oracle —
   simpler and works across implementations.

---

## Outcome (quantified)

| Artifact                          | Files | Lines / Items                      |
| --------------------------------- | ----- | ---------------------------------- |
| Repo overview docs (`docs/`)      | 4     | ~870 lines                         |
| Per-program COBOL reference       | 6     | ~3,800 lines, 44 programs covered  |
| Migration plan + architecture     | 2     | ~520 lines                         |
| Java production code              | 14    | Maven module, Java 17              |
| COBOL oracle + scripts            | 5     | `CBACT04P.cbl` + 3 shell + README  |
| Test suite                        | 8     | **49 unit tests + 1 byte-for-byte IT, all passing** |
| Total new files in repo           | ~50   | All under `docs/` and `app/java/`  |

No edits to existing source under `app/cbl/`, `app/cpy/`, `app/jcl/`, or
the three extension modules.

---

## Lessons learned

- **Plan-then-implement is force-multiplying.** The plan caught the sign
  overpunch issue, the COBOL-vs-Java arithmetic mismatch, and the
  oracle-build approach before any Java was written. Implementation was
  largely mechanical execution.
- **Subagents collapse exploration time.** Five parallel agents reading
  ~30 COBOL programs is the difference between "we can do this" and "we
  can't fit this in context."
- **Test-by-equivalence beats test-by-specification for migrations.**
  The unit tests verify Java behavior; the golden-file test verifies
  Java equals COBOL. Both are needed. The latter is what shifts the
  trust burden from "I think Claude got it right" to "the bytes match."
- **Trust nothing — verify against source.** Module READMEs, comments,
  and even the migration plan itself contained errors that were only
  caught by reading the actual bytes (literal `awk` on the data file)
  or running the tests.
- **Fixtures are where bugs live.** When you've designed for byte-level
  fidelity and the production code is small, your bug surface area
  shifts almost entirely into the test data.
- **Each program migration is a template.** The four leaf utilities
  (`ZonedDecimalCodec`, `FixedWidthFormat`, `Db2Timestamp`,
  `BatchAbendException`) are reusable across every other batch program
  in the repo. Migration #2 (e.g. `CBTRN02C`) should reuse them
  unchanged. Migration #1 paid the foundation cost; migrations #2-N
  should be much cheaper. Crucially, the **GnuCOBOL config flags**
  (`-fsign=EBCDIC` in build.sh, `COB_LS_FIXED=Y` in run.sh) are also
  reusable — the oracle infrastructure ships once and serves all
  subsequent migrations.
- **First-pass byte-for-byte equivalence is rare.** Even with a clean
  plan and careful implementation, the oracle/Java diff failed three
  times in this case study before passing — each time pointing at a
  different GnuCOBOL configuration quirk. Budget for a triage round
  on the IT, separate from the unit-test triage.
- **CLI runs of the Java port are not byte-reproducible without
  timestamp injection.** The IT works because it calls
  `Db2Timestamp.useFixedClock(...)` in `@BeforeAll`. The same
  injection should be exposed via a `--ts=` CLI flag on
  `InterestCalculator.main()` if anyone wants to reproduce outputs
  outside the test framework.

---

## Recommendations for large-scale modernization

1. **Start with discovery, not migration.** Spend the first session
   building a per-program reference, even if you only plan to migrate a
   handful. The reference becomes the source of truth that prevents
   "lost in the codebase" later.
2. **Use parallel subagents for the discovery phase.** Cap at 3–5 per
   batch, scoped to functional slices (online vs. batch vs. extension
   module). One agent per program is excessive; one agent for "all online
   programs" is the right granularity.
3. **Build a byte-for-byte test oracle from day one.** For COBOL, the
   pattern is: take the original, port it minimally to use sequential
   files only (loading lookups into in-memory tables), build with
   GnuCOBOL, run against ASCII fixtures, capture outputs as golden files.
   The test contract is `Files.mismatch(expected, actual) == -1L`.
4. **Memorize the silent killers as you find them.** Sign overpunch
   conventions, rounding modes, timestamp formats, file-status semantics
   — save these to the memory system so they apply to every subsequent
   migration in the same codebase.
5. **Plan-mode every migration of meaningful size.** The cost of writing
   a plan is < 10 % of the implementation cost; the cost of implementing
   the wrong design is 50–100 %.
6. **Migrate one program at a time, ship the foundation.** The
   first migration should produce reusable codec / format / timestamp
   utilities that the next 30 migrations consume. Resist the temptation
   to "do them all in one big PR."
7. **Make tests skip gracefully when their dependencies are missing.**
   The golden-file IT auto-skips when the GnuCOBOL oracle isn't built,
   so a CI environment without `cobc` still runs all 49 unit tests
   instead of failing the build.
8. **Surface decisions to humans, don't autonomously decide them.**
   Java version, build system, test framework, COBOL-oracle approach —
   Claude proposed defaults and had the user accept or override. This
   takes < 1 minute and prevents the "you built the wrong thing" rework
   loop.

---

## Limitations and caveats

- **Single-program scope.** This case study is one batch program. Multi-
  program coordination (shared state, transaction order across
  jobs in a JCL chain) is harder and not demonstrated here.
- **No CICS / IMS / DB2 yet.** The online (CICS) programs and the IMS+DB2
  authorization module are documented but not migrated. They have
  meaningfully different patterns (pseudoconversational state, two-phase
  commit, hierarchical DB navigation) that require different equivalence
  oracles.
- **GnuCOBOL is not z/OS.** The oracle proves Java ≡ portable-COBOL;
  it does NOT prove Java ≡ mainframe-COBOL bit-for-bit. Two known
  divergences had to be papered over with config flags:
  `cobc -fsign=EBCDIC` (otherwise positive-zero signed fields drop
  the overpunch) and `COB_LS_FIXED=Y` (otherwise LINE SEQUENTIAL
  trims trailing spaces). And RECFM=F on the mainframe has no
  record terminators; the oracle uses LINE SEQUENTIAL with `\n`.
  For final acceptance against a real mainframe, an additional
  strip-newlines step plus a real-mainframe smoke test is needed.
  GnuCOBOL ≥ 3.x is required for the `-fsign=EBCDIC` flag.
- **Discovery quality varies with code quality.** CardDemo has clean
  copybooks and named paragraphs. A 40-year-old codebase with
  3-character variable names, GO TO spaghetti, and altered procedure
  divisions would slow the discovery phase substantially.
- **Subagents charge real money.** The five parallel discovery agents
  each ran 50–80 tool calls. Plan the subagent fan-out for the value of
  the documentation produced; don't fan out for tasks the main agent
  can do in 5 calls.

---

## Closing thought

The hardest part of COBOL→Java migration isn't translating syntax — it's
proving that the translation preserves behavior. Claude's contribution
here is less about "writing Java code" (that's the easy 30 %) and more
about (a) finding the silent-killer details in the source, (b) building
a verification harness that surfaces divergence in seconds rather than
production incidents, and (c) keeping the work tractable through
parallel exploration, persistent documentation, and a plan-first
workflow.

Done well, the per-program migration cost amortizes downward. The first
program in this codebase took one session and built four reusable
utility classes. The 30th program should take a fraction of that
because the foundation is already in place.
