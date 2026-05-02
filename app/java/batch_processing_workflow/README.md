# CardDemo Batch Processing Workflow — Java

Java port of CardDemo batch programs. Currently houses a single program:

| COBOL source                | Java entry point                                        | Driving JCL (original) |
| --------------------------- | ------------------------------------------------------- | ---------------------- |
| `app/cbl/CBACT04C.cbl`      | `com.carddemo.batch.interest.InterestCalculator`        | `app/jcl/INTCALC.jcl`  |

Migration plan: [`docs/migration/CBACT04C-to-java-plan.md`](../../../docs/migration/CBACT04C-to-java-plan.md).

## Layout

```
batch_processing_workflow/
├── pom.xml                                      Maven (Java 17, JUnit 5, AssertJ)
├── README.md                                    (this file)
├── HOW-TO-TEST.md                               Test-environment setup + verification workflow
├── src/main/java/com/carddemo/batch/interest/   Java port (domain, io, util, main)
├── src/test/java/                               Unit tests + golden-file IT
├── src/test/resources/fixtures/
│   ├── input/                                   Copies of app/data/ASCII/*.txt
│   ├── synthetic/                               (reserved for hand-crafted edge fixtures)
│   └── expected/                                Golden outputs from the COBOL oracle
└── cobol-reference/                             GnuCOBOL oracle (CBACT04P) — not for production
    ├── CBACT04P.cbl
    ├── README.md
    └── tools/{build.sh,run.sh,regen-golden.sh}
```

## Build

```bash
mvn -q -DskipTests package
```

Produces `target/batch-processing-workflow-1.0.0-SNAPSHOT.jar` with a
`Main-Class` of `InterestCalculator`.

## Run

The program reads 4 input files and writes 2 output files. Paths are
configured via env vars (matching the JCL DD-name convention) or
`--name=path` CLI arguments. The first positional arg is `PARM-DATE`
(10 chars, the prefix used to build transaction IDs).

```bash
java -jar target/batch-processing-workflow-1.0.0-SNAPSHOT.jar 2025-04-29 \
  --tcatbal=src/test/resources/fixtures/input/tcatbal.txt \
  --xref=src/test/resources/fixtures/input/cardxref.txt \
  --acctin=src/test/resources/fixtures/input/acctdata.txt \
  --discgrp=src/test/resources/fixtures/input/discgrp.txt \
  --acctout=/tmp/acctdata.out \
  --transact=/tmp/transact.dat
```

## Test

See [`HOW-TO-TEST.md`](./HOW-TO-TEST.md) — the test workflow has a
GnuCOBOL prerequisite (for the byte-for-byte equivalence check). The
unit-test layer needs no COBOL.

## Design notes

- **Byte-for-byte fidelity is the contract.** Every record class wraps
  the raw 50/300/350-byte buffer and exposes typed getters; updates
  return new instances with only the affected bytes mutated. FILLER and
  any unread fields pass through unchanged.
- **Sign-overpunch encoding** — see `io/ZonedDecimalCodec.java`. The
  ASCII fixtures preserve EBCDIC overpunch chars (`{}A–IJ–R`) on
  signed numeric fields; the codec round-trips every overpunch character.
- **Decimal arithmetic** uses `BigDecimal.divide(…, 2, RoundingMode.DOWN)`
  to match COBOL `COMPUTE` truncation semantics (no `ROUNDED` clause in
  the source).
- **Timestamps** are injectable via `Db2Timestamp.useFixedClock(...)`;
  the test uses a fixed instant so output bytes are deterministic.
- **ABEND** maps to `BatchAbendException` → `System.exit(999)`, mirroring
  the original `CALL 'CEE3ABD'` with code 999.

## Status

- [x] Java port complete
- [x] Unit tests covering codec, format, decimal arithmetic, timestamp,
      disclosure-group fallback, end-to-end on synthetic fixtures
- [x] COBOL oracle (`CBACT04P`) for byte-for-byte verification
- [x] Golden-file IT (`GoldenFileEquivalenceIT`) skips automatically
      until `cobol-reference/tools/regen-golden.sh` has been run
- [ ] Tests not yet executed in this repo (no GnuCOBOL / JDK installed
      in the working environment) — see HOW-TO-TEST.md for the workflow
