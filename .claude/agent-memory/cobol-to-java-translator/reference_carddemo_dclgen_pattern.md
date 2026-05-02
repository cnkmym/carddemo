---
name: CardDemo DCLGEN VARCHAR host-variable pattern
description: How VARCHAR columns surface in CardDemo DCLGEN copybooks and the safe read pattern for DB2 fetched VARCHAR values
type: reference
---

DCLGEN copybooks in `app/app-transaction-type-db2/dcl/` (e.g. `DCLTRTYP.dcl`, `DCLTRCAT.dcl`) follow standard IBM `DCLGEN` output for `VARCHAR` columns:

```cobol
10 DCL-TR-DESCRIPTION.
   49 DCL-TR-DESCRIPTION-LEN  PIC S9(4) USAGE COMP.
   49 DCL-TR-DESCRIPTION-TEXT PIC X(50).
```

The `49`-level pair is the IBM-standard VARCHAR host variable shape: `S9(4) COMP` length followed by fixed-length text buffer.

**Read-side rule:** After `SELECT INTO :DCL-TR-DESCRIPTION` or `FETCH ... INTO :DCL-TR-DESCRIPTION`, only the first `DCL-TR-DESCRIPTION-LEN` bytes of `-TEXT` are meaningful. Any code copying the text MUST use reference modification:

```cobol
MOVE DCL-TR-DESCRIPTION-TEXT(1: DCL-TR-DESCRIPTION-LEN) TO target
```

`COTRTUPC.cbl` line 15240 demonstrates this. Forgetting the reference modification will leave trailing garbage from the previous fetch.

**Write-side rule:** Before `INSERT`/`UPDATE` you MUST set both `-LEN` and `-TEXT`. Pattern from `COTRTUPC` `9600-WRITE-PROCESSING`:

```cobol
MOVE FUNCTION TRIM(source) TO DCL-TR-DESCRIPTION-TEXT
COMPUTE DCL-TR-DESCRIPTION-LEN = FUNCTION LENGTH(source)
```

**Java equivalent:** Maps to a single `String` in JDBC/JPA — no analogue of the length field. Use `@Column(length = 50)` and let the JDBC driver handle padding/truncation.
