# CBACT04C Java Port — Class Architecture

Companion to [`CBACT04C-to-java-plan.md`](./CBACT04C-to-java-plan.md). This
document captures the actual class structure and dependency graph of the
Java port living at
[`app/java/batch_processing_workflow/`](../../app/java/batch_processing_workflow/).

## Dependency graph

```mermaid
graph TD
    classDef mainLayer fill:#dae8fc,stroke:#6c8ebf,color:#000
    classDef domainLayer fill:#d5e8d4,stroke:#82b366,color:#000
    classDef ioLayer fill:#ffe6cc,stroke:#d79b00,color:#000
    classDef utilLayer fill:#f5f5f5,stroke:#666,color:#000

    subgraph PKG_MAIN["package com.carddemo.batch.interest"]
        IC["InterestCalculator<br/>main entry · mirrors PROCEDURE DIVISION<br/>+ nested Config record"]
    end

    subgraph PKG_DOMAIN["package .domain — record wrappers (raw bytes preserve FILLER)"]
        AR["AccountRecord<br/>(300 bytes)"]
        TCB["TransactionCategoryBalanceRecord<br/>(50 bytes)"]
        CX["CardXrefRecord<br/>(50 bytes)"]
        DG["DisclosureGroupRecord<br/>(50 bytes)"]
        TR["TransactionRecord<br/>(350 bytes)"]
    end

    subgraph PKG_IO["package .io — file readers/writers/stores"]
        AF["AccountFile<br/>load + lookup + update + flush"]
        CXF["CardXrefFile<br/>load + lookup-by-acct-id (AIX)"]
        DGF["DisclosureGroupFile<br/>load + DEFAULT-fallback lookup"]
        TCR["TcatBalReader<br/>sequential iteration"]
        TW["TransactionWriter<br/>append-only stream"]
        ZDC["ZonedDecimalCodec<br/>EBCDIC overpunch ⇄ BigDecimal"]
        FWF["FixedWidthFormat<br/>text + unsigned numeric helpers"]
    end

    subgraph PKG_UTIL["package .util — cross-cutting"]
        DT["Db2Timestamp<br/>injectable Clock<br/>(YYYY-MM-DD-HH.MM.SS.MM0000)"]
        BAE["BatchAbendException<br/>maps to System.exit(999)"]
    end

    IC --> AF
    IC --> CXF
    IC --> DGF
    IC --> TCR
    IC --> TW
    IC --> AR
    IC --> CX
    IC --> DG
    IC --> TCB
    IC --> TR
    IC --> DT
    IC --> BAE

    AF --> AR
    CXF --> CX
    DGF --> DG
    TCR --> TCB
    TW --> TR

    AF --> FWF
    AF --> BAE
    CXF --> FWF
    CXF --> BAE
    DGF --> FWF
    DGF --> BAE
    TCR --> FWF

    AR --> ZDC
    AR --> FWF
    CX --> FWF
    DG --> ZDC
    DG --> FWF
    TCB --> ZDC
    TCB --> FWF
    TR --> ZDC
    TR --> FWF

    class IC mainLayer
    class AR,CX,DG,TCB,TR domainLayer
    class AF,CXF,DGF,TCR,TW,ZDC,FWF ioLayer
    class DT,BAE utilLayer
```

## How to read it

Edges go **from the user to the dependency** — `A --> B` means "A imports
or uses B". The graph is acyclic and forms a clean 4-layer hierarchy:

1. **`InterestCalculator`** (top) — the only orchestrator. Touches every
   other class. Mirrors the COBOL `PROCEDURE DIVISION` 1:1 logically.
2. **`.io` file-store classes** (`AccountFile`, `CardXrefFile`,
   `DisclosureGroupFile`, `TcatBalReader`, `TransactionWriter`) — wrap
   collections of records, do the actual I/O, and ABEND on missing keys.
3. **`.domain` record classes** — wrap their raw byte buffers and expose
   typed getters/withers via the codec helpers. Raw bytes preserve FILLER
   and any unread fields, so re-encoding is byte-identical to decoding.
4. **`.io` codec helpers + `.util`** (the leaves: `ZonedDecimalCodec`,
   `FixedWidthFormat`, `Db2Timestamp`, `BatchAbendException`) — no
   outgoing internal dependencies. These are the foundation everything
   else builds on.

## Two design choices the diagram surfaces

- **`ZonedDecimalCodec` and `FixedWidthFormat` live in `.io` even though
  `.domain` records depend on them.** They're the byte-level I/O
  primitives, so the package boundary is honest — the domain records
  cannot be byte-accurate without these.
- **`Db2Timestamp` only has one caller (`InterestCalculator`).** It's a
  singleton-ish helper because both the COBOL oracle and the Java port
  need to inject the same fixed instant for byte-for-byte equivalence;
  the `Clock` indirection is the test seam.

## File-system map

```
app/java/batch_processing_workflow/src/main/java/com/carddemo/batch/interest/
├── InterestCalculator.java
├── domain/
│   ├── AccountRecord.java
│   ├── CardXrefRecord.java
│   ├── DisclosureGroupRecord.java
│   ├── TransactionCategoryBalanceRecord.java
│   └── TransactionRecord.java
├── io/
│   ├── ZonedDecimalCodec.java
│   ├── FixedWidthFormat.java
│   ├── AccountFile.java
│   ├── CardXrefFile.java
│   ├── DisclosureGroupFile.java
│   ├── TcatBalReader.java
│   └── TransactionWriter.java
└── util/
    ├── Db2Timestamp.java
    └── BatchAbendException.java
```
