# CBACT01C Java Port — Class Architecture

Companion to [`CBACT01C-to-java-plan.md`](./CBACT01C-to-java-plan.md). This document captures the
planned class structure and dependency graph for the Java port at
[`app/java/batch_processing_workflow/`](../../app/java/batch_processing_workflow/).

## Dependency graph

```mermaid
graph TD
    classDef mainLayer   fill:#dae8fc,stroke:#6c8ebf,color:#000
    classDef domainLayer fill:#d5e8d4,stroke:#82b366,color:#000
    classDef ioLayer     fill:#ffe6cc,stroke:#d79b00,color:#000
    classDef utilLayer   fill:#f5f5f5,stroke:#666,color:#000
    classDef reusedLayer fill:#e1d5e7,stroke:#9673a6,color:#000

    subgraph PKG_MAIN["package com.carddemo.batch.account"]
        AE["AccountExtractor<br/>main entry · mirrors PROCEDURE DIVISION<br/>carries outCycDebit loop state"]
    end

    subgraph PKG_DOMAIN["package .account.domain — record wrappers"]
        EAR["ExtractAccountRecord<br/>(300 bytes — all 11 CVACT01Y fields)"]
        OAR["OutAccountRecord<br/>(107 bytes — zoned + COMP-3)"]
        ARR["ArrayRecord<br/>(110 bytes — OCCURS 5, zoned + COMP-3)"]
        V1R["Vbr1Record<br/>(12 bytes)"]
        V2R["Vbr2Record<br/>(39 bytes)"]
    end

    subgraph PKG_IO["package .account.io — file readers/writers/codecs"]
        AFR["AccountFileReader<br/>sequential 300-byte lines"]
        OAW["OutAccountWriter<br/>append-only 107-byte stream"]
        ARW["ArrayRecordWriter<br/>append-only 110-byte stream"]
        VRW["VbrRecordWriter<br/>two files: vbr1.dat (12B) + vbr2.dat (39B)"]
        PDC["PackedDecimalCodec<br/>COMP-3 BCD ⇄ BigDecimal<br/>(NEW — no equivalent in interest.io)"]
    end

    subgraph PKG_UTIL["package .account.util"]
        DC["DateConverter<br/>COBDATFT replacement<br/>YYYY-MM-DD → YYYYMMDD__(10 chars)"]
    end

    subgraph PKG_REUSED["reused from com.carddemo.batch.interest (unchanged)"]
        ZDC["ZonedDecimalCodec<br/>EBCDIC overpunch ⇄ BigDecimal"]
        FWF["FixedWidthFormat<br/>text + unsigned numeric helpers"]
        BAE["BatchAbendException<br/>maps to System.exit(999)"]
    end

    AE --> AFR
    AE --> OAW
    AE --> ARW
    AE --> VRW
    AE --> EAR
    AE --> OAR
    AE --> ARR
    AE --> V1R
    AE --> V2R
    AE --> DC
    AE --> BAE

    AFR --> EAR
    OAW --> OAR
    ARW --> ARR
    VRW --> V1R
    VRW --> V2R

    AFR --> FWF
    AFR --> BAE
    OAW --> BAE
    ARW --> BAE
    VRW --> BAE

    EAR --> ZDC
    EAR --> FWF
    OAR --> ZDC
    OAR --> PDC
    OAR --> FWF
    ARR --> ZDC
    ARR --> PDC
    ARR --> FWF
    V1R --> FWF
    V2R --> ZDC
    V2R --> FWF

    class AE mainLayer
    class EAR,OAR,ARR,V1R,V2R domainLayer
    class AFR,OAW,ARW,VRW,PDC ioLayer
    class DC utilLayer
    class ZDC,FWF,BAE reusedLayer
```

## How to read it

Edges go **from the user to the dependency** — `A --> B` means "A imports or uses B". The graph
is acyclic and forms a clean layered hierarchy:

1. **`AccountExtractor`** (top) — the only orchestrator. Touches every other class. Mirrors the
   COBOL `PROCEDURE DIVISION` one-to-one, including the `outCycDebit` loop-state variable that
   replicates the sticky-debit carry-over behaviour (§5.3 of the plan).
2. **`.io` file-store classes** (`AccountFileReader`, `OutAccountWriter`, `ArrayRecordWriter`,
   `VbrRecordWriter`) — wrap byte-level I/O and ABEND on error.
3. **`.domain` record classes** — wrap their raw byte buffers and expose typed getters/builders
   via the codec helpers. Raw bytes preserve FILLER, ensuring re-encoding is byte-identical.
4. **Codec and utility leaves** (`PackedDecimalCodec`, `ZonedDecimalCodec`, `FixedWidthFormat`,
   `DateConverter`, `BatchAbendException`) — no outgoing internal dependencies. These are the
   foundation everything else builds on.

## Contrast with the CBACT04C port

| Aspect | CBACT04C (`interest` package) | CBACT01C (`account` package) |
| ------ | ----------------------------- | ----------------------------- |
| Codec | `ZonedDecimalCodec` only | `ZonedDecimalCodec` **+** new `PackedDecimalCodec` (COMP-3) |
| Output files | 2 (TRANSACT + ACCTFILE rewrite) | 4 (OUTFILE + ARRYFILE + VBR1 + VBR2) |
| Timestamp injection | Required (DB2 timestamp) | Not needed (no `CURRENT-DATE`) |
| External call | None | `COBDATFT` assembler → `DateConverter` |
| Loop state | `wsFirstTime` + `wsTotalInt` per account group | `outCycDebit` across all records |
| Inter-file lookup | Yes (XREF, DISCGRP, ACCOUNT random reads) | No (single sequential input) |
| GnuCOBOL changes | INDEXED → LINE SEQUENTIAL for 4 files | INDEXED → LINE SEQUENTIAL for 1 file; VBR → 2 fixed files; COBDATFT inlined |

## File-system map

```
app/java/batch_processing_workflow/src/main/java/com/carddemo/batch/account/
├── AccountExtractor.java
├── domain/
│   ├── ExtractAccountRecord.java
│   ├── OutAccountRecord.java
│   ├── ArrayRecord.java
│   ├── Vbr1Record.java
│   └── Vbr2Record.java
├── io/
│   ├── PackedDecimalCodec.java
│   ├── AccountFileReader.java
│   ├── OutAccountWriter.java
│   ├── ArrayRecordWriter.java
│   └── VbrRecordWriter.java
└── util/
    └── DateConverter.java
```

Reused from `com.carddemo.batch.interest` (no changes):

```
app/java/batch_processing_workflow/src/main/java/com/carddemo/batch/interest/
├── io/
│   ├── ZonedDecimalCodec.java      ← imported by EAR, OAR, ARR, V2R
│   └── FixedWidthFormat.java       ← imported by all domain + IO classes
└── util/
    └── BatchAbendException.java    ← imported by AE and all IO classes
```

## GnuCOBOL oracle layout

```
app/java/batch_processing_workflow/cobol-reference/
├── CBACT04P.cbl          (existing)
├── CBACT01P.cbl          (new — sequential-file portable oracle for CBACT01C)
└── tools/
    ├── build.sh          (extended to compile CBACT01P)
    ├── run.sh            (extended to run CBACT01P against fixtures/input/)
    └── regen-golden.sh   (extended to copy CBACT01P outputs → fixtures/expected/)
```

`CBACT01P.cbl` changes relative to `CBACT01C.cbl`:

| Change | Detail |
| ------ | ------ |
| `ACCTFILE-FILE` | `INDEXED SEQUENTIAL` → `LINE SEQUENTIAL INPUT` |
| `VBRC-FILE` | Replaced by two `RECORD SEQUENTIAL` files: `VBRC1-FILE` (12 B) and `VBRC2-FILE` (39 B) |
| `CALL 'COBDATFT'` | Replaced with inline COBOL: `STRING CODATECN-1O-YYYY CODATECN-1O-MM CODATECN-1O-DD DELIMITED SIZE INTO CODATECN-0UT-DATE` then space-fill remainder |
| All business logic paragraphs | Copied verbatim from `CBACT01C` |
