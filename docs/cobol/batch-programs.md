# Batch COBOL Programs

This document is a per-program reference for the 13 batch and shared-utility
COBOL sources under `app/cbl/`. It is the COBOL-level companion to
`docs/architecture.md` (which describes the nightly chain at the JCL level)
and `docs/directory-guide.md` (which lists every file in the tree).

## What the batch tier does

Every batch program is invoked as a single mainframe job step under JCL:
`JCL job → STEP EXEC PGM=CBxxxxxC → COBOL load module`. The COBOL
program does not see the job stream; it sees only the DD names that JCL
binds to its `SELECT ... ASSIGN TO <ddname>` clauses, which in turn point at
VSAM clusters or sequential PS / GDG datasets. The shared shape across the
catalog is:

- **Open all files** in dedicated `OPEN` paragraphs, each verifying the
  two-byte file-status code against `'00'`.
- **Drive a record-at-a-time loop** with `PERFORM UNTIL END-OF-FILE = 'Y'`
  that calls a `1000-...-GET-NEXT` paragraph.
- **Translate file status to an application result code** held in
  `APPL-RESULT` (an `S9(9) COMP` int), with `88`-level condition names
  `APPL-AOK` (0) and `APPL-EOF` (16).
- **Abend on any unexpected status** by calling the LE service
  `CEE3ABD` (with abend code 999) from `9999-ABEND-PROGRAM`.
- **Close all files** in dedicated `9xxx-...-CLOSE` paragraphs and
  `GOBACK` to the JCL step.

A `9910-DISPLAY-IO-STATUS` helper reformats binary file-status codes
(status `'9x'`, where the second byte is a binary integer) into a printable
4-digit form before they are written to `SYSOUT`. This block is copy-pasted
verbatim into every program in this catalog. There is no shared exception
copybook — each program rolls its own.

## Catalog

| COBOL program | Driving JCL | One-line purpose | Inputs | Outputs |
|---|---|---|---|---|
| `CBACT01C` | `app/jcl/READACCT.jcl` | Dump account master + exercise FB / VB / array file outputs | `ACCTFILE` (KSDS) | `OUTFILE` FB, `ARRYFILE` FB, `VBRCFILE` VB |
| `CBACT02C` | `app/jcl/READCARD.jcl` | Dump card master to `SYSOUT` | `CARDFILE` (KSDS) | `SYSOUT` |
| `CBACT03C` | `app/jcl/READXREF.jcl` | Dump card / account / customer cross-reference | `XREFFILE` (KSDS) | `SYSOUT` |
| `CBACT04C` | `app/jcl/INTCALC.jcl` | Compute monthly interest, write system-generated transactions | `TCATBALF`, `XREFFILE` (with AIX), `DISCGRP` (KSDS), `ACCTFILE` (KSDS, I-O) | `TRANSACT` (PS) |
| `CBCUS01C` | `app/jcl/READCUST.jcl` | Dump customer master to `SYSOUT` | `CUSTFILE` (KSDS) | `SYSOUT` |
| `CBTRN01C` | (none in repo) | Reference / scaffold posting program — reads `DALYTRAN` and looks up xref + account | `DALYTRAN` PS, `XREFFILE`, `CUSTFILE`, `CARDFILE`, `ACCTFILE`, `TRANFILE` (all KSDS) | `SYSOUT` only |
| `CBTRN02C` | `app/jcl/POSTTRAN.jcl` | Production daily transaction poster | `DALYTRAN` PS, `XREFFILE` KSDS, `TRANFILE` KSDS (output), `ACCTFILE` and `TCATBALF` KSDS (I-O) | `TRANFILE` writes, `DALYREJS` GDG |
| `CBTRN03C` | `app/jcl/TRANREPT.jcl` | Detail transaction report (paginated, with totals) | `TRANFILE` PS (sorted), `CARDXREF`, `TRANTYPE`, `TRANCATG` KSDS, `DATEPARM` PS | `TRANREPT` PS (FB 133) |
| `CBSTM03A` | `app/jcl/CREASTMT.JCL` | Generate per-account statement in plain text and HTML | calls `CBSTM03B` for all VSAM I/O | `STMTFILE` FB 80, `HTMLFILE` FB 100 |
| `CBSTM03B` | (called from `CBSTM03A`) | Generic file-handler subprogram for `CBSTM03A` | `TRNXFILE`, `XREFFILE`, `CUSTFILE`, `ACCTFILE` (all KSDS) | none directly |
| `CBEXPORT` | `app/jcl/CBEXPORT.jcl` | Multi-record branch-migration export | `CUSTFILE`, `ACCTFILE`, `XREFFILE`, `TRANSACT`, `CARDFILE` (all KSDS) | `EXPFILE` (KSDS, RECSIZE 500) |
| `CBIMPORT` | `app/jcl/CBIMPORT.jcl` | Multi-record branch-migration import | `EXPFILE` (KSDS) | `CUSTOUT`, `ACCTOUT`, `XREFOUT`, `TRNXOUT`, `CARDOUT` PS + `ERROUT` PS |
| `CSUTLDTC` | (none — called as subroutine) | Validate a date via LE `CEEDAYS` | linkage parameters | linkage parameters; `RETURN-CODE` = severity |

## `CBACT01C` — Account file processor / output-file demonstrator

**Purpose.** Read every record in the account master and copy each one out
in three distinct sequential formats; primarily a teaching program that
exercises FB, VB and OCCURS-array record layouts in one program.

**Driving JCL.** `app/jcl/READACCT.jcl` (single step `STEP05`).

**Key copybooks.**
- `CVACT01Y` — `ACCOUNT-RECORD` (300 bytes, see [Shared copybooks](#shared-copybooks-quick-reference)).
- `CODATECN` — input/output area for the assembler date-format helper `COBDATFT`.

**Files / datasets.**
- `ACCTFILE` — KSDS, sequential read, 300-byte record (`PIC 9(11)` key + 289 of payload).
- `OUTFILE` — sequential FB, LRECL 107 (account fields, partly `COMP-3`).
- `ARRYFILE` — sequential FB, LRECL 110, with an `OCCURS 5 TIMES` group.
- `VBRCFILE` — sequential **VB** with `RECORD IS VARYING IN SIZE FROM 10 TO 80 DEPENDING ON WS-RECD-LEN`.

**Programs called.**
- `COBDATFT` (assembler, `app/asm/COBDATFT.asm`) — splits `YYYYMMDD` /
  inserts dashes; called via `CALL 'COBDATFT' USING CODATECN-REC`.
- `CEE3ABD` — Language Environment abend.

**Major paragraphs / control flow.**
- `0000-ACCTFILE-OPEN` / `2000-OUTFILE-OPEN` / `3000-ARRFILE-OPEN` /
  `4000-VBRFILE-OPEN` open each file individually.
- Mainline `PERFORM UNTIL END-OF-FILE = 'Y'` calls `1000-ACCTFILE-GET-NEXT`.
- For each input record `1000` chains `1100-DISPLAY-ACCT-RECORD`,
  `1300-POPUL-ACCT-RECORD` (calls `COBDATFT` for date reformat),
  `1350-WRITE-ACCT-RECORD`, `1400-POPUL-ARRAY-RECORD`,
  `1450-WRITE-ARRY-RECORD`, then `1500/1550/1575` build and write **two**
  variable-length records into `VBRCFILE`.
- EOF detected by file status `'10'`; `9000-ACCTFILE-CLOSE` finishes.

**Notable COBOL patterns.**
- `OCCURS 5 TIMES` group (`ARR-ACCT-BAL`) — the array element mixes
  `DISPLAY` and `COMP-3` numerics.
- `RECORD IS VARYING IN SIZE ... DEPENDING ON WS-RECD-LEN` — variable
  length write controlled by writing into a substring `VBR-REC(1:WS-RECD-LEN)`
  before each `WRITE`.
- Mixed `USAGE IS COMP-3` (packed decimal) and default `DISPLAY` zoned
  decimal in the same FD — care needed for transpilers.
- `9910-DISPLAY-IO-STATUS` decodes binary-format status (`'9x'`) by
  `REDEFINES`-aliasing a `PIC 9(4) BINARY` over two `PIC X` bytes.

## `CBACT02C` — Card file processor

**Purpose.** Sequentially read the card master and `DISPLAY` every record;
the simplest reader template in the catalog.

**Driving JCL.** `app/jcl/READCARD.jcl`.

**Key copybooks.**
- `CVACT02Y` — `CARD-RECORD` (150 bytes; `CARD-NUM` PIC X(16) primary key,
  `CARD-CVV-CD` PIC 9(03), embossed name, expiry, status).

**Files / datasets.**
- `CARDFILE` — KSDS, sequential read, 150-byte record (key `FD-CARD-NUM`).

**Programs called.**
- `CEE3ABD` — only on error.

**Major paragraphs / control flow.**
- `0000-CARDFILE-OPEN` opens for input.
- `PERFORM UNTIL END-OF-FILE` drives `1000-CARDFILE-GET-NEXT`; success
  path simply `DISPLAY CARD-RECORD`. EOF detected by status `'10'`.
- `9000-CARDFILE-CLOSE` finishes; `GOBACK`.

**Notable COBOL patterns.**
- This program is the canonical "single-file reader" template re-used
  (with mechanical copy/paste edits) in `CBACT03C` and `CBCUS01C`.
- `ADD 8 TO ZERO GIVING APPL-RESULT` and
  `SUBTRACT APPL-RESULT FROM APPL-RESULT` in the close paragraph are
  hand-written equivalents of `MOVE 8 TO APPL-RESULT` / `MOVE 0 TO ...`;
  the dialect quirk is intentional and worth flagging in any analyzer.

## `CBACT03C` — Card-account cross-reference processor

**Purpose.** Sequentially read the card / account / customer cross-reference
KSDS and `DISPLAY` every record.

**Driving JCL.** `app/jcl/READXREF.jcl`.

**Key copybooks.**
- `CVACT03Y` — `CARD-XREF-RECORD` (50 bytes:
  `XREF-CARD-NUM PIC X(16)` + `XREF-CUST-ID PIC 9(09)` +
  `XREF-ACCT-ID PIC 9(11)` + filler).

**Files / datasets.**
- `XREFFILE` — KSDS, sequential read, 50-byte record (key
  `FD-XREF-CARD-NUM`).

**Programs called.**
- `CEE3ABD` — only on error.

**Major paragraphs / control flow.**
- `0000-XREFFILE-OPEN`, mainline loop driving `1000-XREFFILE-GET-NEXT`,
  `9000-XREFFILE-CLOSE`. Identical control flow to `CBACT02C`.
- Notable difference: the success branch `DISPLAY`s the record both inside
  `1000-XREFFILE-GET-NEXT` and in the mainline loop. A duplicate display.

**Notable COBOL patterns.**
- Same `9910-DISPLAY-IO-STATUS` boilerplate as the rest of the family.
- The cross-reference VSAM cluster is read with **one** key in this
  program; in `CBACT04C` the same logical file is opened with a separate
  `ALTERNATE RECORD KEY IS FD-XREF-ACCT-ID` for AIX-driven random reads —
  worth noting when a transpiler tries to share the FD definition.

## `CBACT04C` — Interest calculator

> **Java port available** — see [`app/java/batch_processing_workflow/`](../../app/java/batch_processing_workflow/) and the migration plan at [`docs/migration/CBACT04C-to-java-plan.md`](../migration/CBACT04C-to-java-plan.md). Byte-for-byte equivalence is verified against a portable GnuCOBOL build of this program.

**Purpose.** For every transaction-category-balance row, look up the owning
account and its disclosure group, compute monthly interest
(`balance * rate / 1200`), accumulate it onto the account's current
balance, and emit one synthetic transaction per category.

**Driving JCL.** `app/jcl/INTCALC.jcl` (passes a 10-character date in
`PARM='2022071800'`, picked up by `EXTERNAL-PARMS` in the linkage section).

**Key copybooks.**
- `CVTRA01Y` — `TRAN-CAT-BAL-RECORD` (compound key
  `TRANCAT-ACCT-ID + TRANCAT-TYPE-CD + TRANCAT-CD`).
- `CVACT03Y` — `CARD-XREF-RECORD` (used for AIX-by-account-id lookup).
- `CVTRA02Y` — `DIS-GROUP-RECORD` with the `DIS-INT-RATE PIC S9(04)V99` rate.
- `CVACT01Y` — `ACCOUNT-RECORD` (read I-O, rewritten in-place).
- `CVTRA05Y` — `TRAN-RECORD` (350-byte format that this program writes out).

**Files / datasets.**
- `TCATBALF` — KSDS, **sequential** input (drives the loop).
- `XREFFILE` — KSDS opened **`RANDOM`** with `ALTERNATE RECORD KEY IS
  FD-XREF-ACCT-ID` — driven by an AIX path (`XREFFIL1` DD).
- `DISCGRP` — KSDS, random read keyed on `(group, type, cat)`.
- `ACCTFILE` — KSDS opened `I-O`, `REWRITE`-ed to update balance and zero
  the cycle credit/debit fields.
- `TRANSACT` — sequential PS `OUTPUT`, system-generated interest
  transactions go here (GDG `+1`).

**Programs called.**
- `CEE3ABD` — abend only.

**Major paragraphs / control flow.**
- Five OPEN paragraphs (`0000`–`0400`).
- Mainline loop reads next `TCATBAL` row. On account-id break it calls
  `1050-UPDATE-ACCOUNT` (rewrites the previous account), then for the
  new account fetches the master via `1100-GET-ACCT-DATA` and the
  cross-reference via `1110-GET-XREF-DATA` (using the `ACCT-ID` AIX).
- `1200-GET-INTEREST-RATE` reads the disclosure group; on file status
  `'23'` (not found) it falls back to `'DEFAULT'` and re-reads via
  `1200-A-GET-DEFAULT-INT-RATE`.
- `1300-COMPUTE-INTEREST` does
  `COMPUTE WS-MONTHLY-INT = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200`.
- `1300-B-WRITE-TX` synthesizes a transaction id by `STRING`-ing
  `PARM-DATE` and an incrementing `WS-TRANID-SUFFIX`, sets type `'01'`
  cat `'05'` source `'System'`, fills timestamps via
  `Z-GET-DB2-FORMAT-TIMESTAMP`, and writes to `TRANSACT`.
- `1400-COMPUTE-FEES` is a stub.
- Five CLOSE paragraphs (`9000`–`9400`).

**Notable COBOL patterns.**
- `LINKAGE SECTION` with `PROCEDURE DIVISION USING EXTERNAL-PARMS`
  receives the JCL `PARM=` string. `PARM-LENGTH PIC S9(04) COMP` is the
  half-word length prefix produced by JCL.
- `READ ... INVALID KEY ... END-READ` for VSAM random reads.
- `IF DISCGRP-STATUS = '00' OR '23'` — abbreviated condition (the
  shorthand "or-list" form, equivalent to
  `STATUS = '00' OR STATUS = '23'`).
- `Z-GET-DB2-FORMAT-TIMESTAMP` builds a DB2-shaped timestamp
  `YYYY-MM-DD-HH.MI.SS.HHmmrr` by `MOVE`ing pieces from
  `FUNCTION CURRENT-DATE` into a `REDEFINES` of a 26-byte buffer — a
  pattern this program shares with `CBTRN02C`.
- `REWRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD` requires the file to be
  opened `I-O`. The mainline cleverly fires `1050-UPDATE-ACCOUNT` from
  the EOF branch as well so the last account is also rewritten.

## `CBCUS01C` — Customer file processor

**Purpose.** Sequentially read the customer master and `DISPLAY` every
record.

**Driving JCL.** `app/jcl/READCUST.jcl`.

**Key copybooks.**
- `CVCUS01Y` — `CUSTOMER-RECORD` (500 bytes; SSN, FICO, address, two
  phone numbers, EFT account id, primary-card-holder flag).

**Files / datasets.**
- `CUSTFILE` — KSDS, sequential read, 500-byte record (key
  `FD-CUST-ID PIC 9(09)`).

**Programs called.**
- `CEE3ABD`.

**Major paragraphs / control flow.**
- `0000-CUSTFILE-OPEN`, mainline `PERFORM UNTIL END-OF-FILE = 'Y'`
  driving `1000-CUSTFILE-GET-NEXT`, `9000-CUSTFILE-CLOSE`.
- Same template as `CBACT02C` / `CBACT03C`. Difference: the abend and
  status helpers are renamed `Z-ABEND-PROGRAM` / `Z-DISPLAY-IO-STATUS`
  (instead of `9999-...` / `9910-...`) — purely cosmetic but easy to
  miss when grepping the catalog.

**Notable COBOL patterns.**
- Like `CBACT03C`, the success branch `DISPLAY`s the customer record
  inside `1000-CUSTFILE-GET-NEXT` *and* the mainline `IF` — duplicate
  output is intentional in the original.

## `CBTRN01C` — Reference daily-transaction reader

**Purpose.** Read each daily transaction, look up its card on `XREFFILE`
and its account on `ACCTFILE`. Reports lookups via `DISPLAY` only — does
**not** post transactions or update balances. Effectively a scaffold /
reference implementation; the production posting program is `CBTRN02C`.

**Driving JCL.** None in `app/jcl/`. The compiled load module is built but
not invoked by any nightly job; treat as a reference / candidate for
future use.

**Key copybooks.**
- `CVTRA06Y` — `DALYTRAN-RECORD` (350 bytes, structurally a clone of
  `CVTRA05Y` but renamed with the `DALYTRAN-` prefix).
- `CVCUS01Y`, `CVACT03Y`, `CVACT02Y`, `CVACT01Y`, `CVTRA05Y` — read for
  side lookups.

**Files / datasets.**
- `DALYTRAN` — sequential PS, sequential read.
- `CUSTFILE`, `XREFFILE`, `CARDFILE`, `ACCTFILE`, `TRANFILE` — all KSDS,
  all opened `RANDOM`.

**Programs called.**
- `CEE3ABD` only.

**Major paragraphs / control flow.**
- Six OPEN paragraphs (`0000`–`0500`).
- Mainline reads next `DALYTRAN` row; on success
  `2000-LOOKUP-XREF` resolves card → account-id, and `3000-READ-ACCOUNT`
  fetches the account record. On `INVALID KEY` it flips a local return
  status (`WS-XREF-READ-STATUS`, `WS-ACCT-READ-STATUS`) and the mainline
  `DISPLAY`s a warning. No file is written.
- Six CLOSE paragraphs (`9000`–`9500`).

**Notable COBOL patterns.**
- `READ ... KEY IS FD-XREF-CARD-NUM INVALID KEY ... NOT INVALID KEY ... END-READ`
  — the standard VSAM random-read with both branches.
- Useful as a comparison point for what `CBTRN02C` adds on top
  (validation, balance updates, reject file).

## `CBTRN02C` — Daily transaction poster (production)

**Purpose.** Validate each row in `DALYTRAN` against the cross-reference
and account master; for valid transactions, update the running
transaction-category balance, update the account current balance and
cycle counters, and write the transaction into the master `TRANFILE`. Any
validation failure goes to the `DALYREJS` GDG with a structured trailer.
Sets `RETURN-CODE = 4` if any rejects were produced.

**Driving JCL.** `app/jcl/POSTTRAN.jcl` (single step `STEP15`).

**Key copybooks.**
- `CVTRA06Y` — input `DALYTRAN-RECORD`.
- `CVTRA05Y` — output `TRAN-RECORD`.
- `CVACT03Y` — `CARD-XREF-RECORD` for card → account lookup.
- `CVACT01Y` — `ACCOUNT-RECORD` (random/I-O for balance update).
- `CVTRA01Y` — `TRAN-CAT-BAL-RECORD` (random/I-O; new rows created on
  miss).

**Files / datasets.**
- `DALYTRAN` — sequential PS input (drives loop).
- `TRANFILE` — KSDS, opened `OUTPUT` — wholesale rewrite of master.
- `XREFFILE` — KSDS, random.
- `ACCTFILE` — KSDS, `I-O` (rewritten per posting).
- `TCATBALF` — KSDS, `I-O` (record either `REWRITE`-en or `WRITE`-en).
- `DALYREJS` — sequential PS, GDG `(+1)`, rejected rows + 80-byte
  validation trailer.

**Programs called.**
- `CEE3ABD`.

**Major paragraphs / control flow.**
- Six OPEN paragraphs (`0000`–`0500`).
- Mainline `PERFORM UNTIL END-OF-FILE`. For each row it clears
  `WS-VALIDATION-FAIL-REASON` (a `PIC 9(04)`) and runs `1500-VALIDATE-TRAN`.
- `1500-A-LOOKUP-XREF` random-reads `XREFFILE` keyed on
  `DALYTRAN-CARD-NUM`. On `INVALID KEY` sets reason **100**.
- `1500-B-LOOKUP-ACCT` random-reads `ACCOUNT-FILE` keyed on the
  resolved `XREF-ACCT-ID`. On hit it computes
  `WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT`
  and rejects with reason **102** if it exceeds `ACCT-CREDIT-LIMIT`.
  Reason **103** is "transaction received after acct expiration"
  (string compare of `ACCT-EXPIRAION-DATE` vs `DALYTRAN-ORIG-TS(1:10)`).
- On `WS-VALIDATION-FAIL-REASON = 0` `2000-POST-TRANSACTION` runs:
  it `MOVE`s every `DALYTRAN-...` field across to its `TRAN-...`
  counterpart, sets `TRAN-PROC-TS` from the live timestamp, then chains
  `2700-UPDATE-TCATBAL` → `2800-UPDATE-ACCOUNT-REC` → `2900-WRITE-TRANSACTION-FILE`.
- `2700-UPDATE-TCATBAL` reads the row by `(acct, type, cat)`. On
  status `'23'` it sets a flag and `2700-A-CREATE-TCATBAL-REC` `WRITE`s
  a new row; otherwise `2700-B-UPDATE-TCATBAL-REC` `ADD`s the amount
  and `REWRITE`s.
- `2800-UPDATE-ACCOUNT-REC` adds the amount to `ACCT-CURR-BAL` then
  to either `ACCT-CURR-CYC-CREDIT` (if non-negative) or
  `ACCT-CURR-CYC-DEBIT`, then `REWRITE`s.
- Six CLOSE paragraphs; final `RETURN-CODE = 4` on any reject.

**Notable COBOL patterns.**
- Reject record built by concatenating the original 350-byte `DALYTRAN`
  row and an 80-byte `WS-VALIDATION-TRAILER` with a numeric reason and a
  76-byte description — this is the "reject record + trailer" pattern;
  consumers of `DALYREJS` rely on the fixed 350+80=430 layout.
- `Z-GET-DB2-FORMAT-TIMESTAMP` produces the same DB2-format
  `YYYY-MM-DD-HH.MI.SS.MIL0000` literal as in `CBACT04C`.
- `IF TCATBALF-STATUS = '00' OR '23'` — same "or-list" idiom as
  `CBACT04C`.
- `RETURN-CODE` (a special-name register in COBOL) is set so the JCL
  step return code reflects "warning, with rejects".

## `CBTRN03C` — Transaction detail report

**Purpose.** Produce a paginated, banded, formatted detail report of all
transactions whose processing date falls inside an inclusive
`[WS-START-DATE, WS-END-DATE]` window read from `DATEPARM`. Subtotals at
account-break, page-break and grand-total.

**Driving JCL.** `app/jcl/TRANREPT.jcl` (multi-step: REPRO master ->
DFSORT to filter and sort -> `CBTRN03C`).

**Key copybooks.**
- `CVTRA05Y` — `TRAN-RECORD`.
- `CVACT03Y` — `CARD-XREF-RECORD`.
- `CVTRA03Y` — `TRAN-TYPE-RECORD` (description by 2-byte type code).
- `CVTRA04Y` — `TRAN-CAT-RECORD` (description by `(type, cat)`).
- `CVTRA07Y` — all the report layouts:
  `REPORT-NAME-HEADER`, `TRANSACTION-DETAIL-REPORT`,
  `TRANSACTION-HEADER-1`, `TRANSACTION-HEADER-2` (133-byte banner of
  dashes), `REPORT-PAGE-TOTALS`, `REPORT-ACCOUNT-TOTALS`,
  `REPORT-GRAND-TOTALS`.

**Files / datasets.**
- `TRANFILE` — sequential input, the daily-filtered, card-number-sorted
  output of the JCL's preceding `SORT` step.
- `CARDXREF`, `TRANTYPE`, `TRANCATG` — KSDS random for descriptions.
- `DATEPARM` — sequential PS, single record `YYYY-MM-DD YYYY-MM-DD`.
- `TRANREPT` — sequential FB output, LRECL 133.

**Programs called.**
- `CEE3ABD`.

**Major paragraphs / control flow.**
- Six OPEN paragraphs (`0000`–`0500`).
- `0550-DATEPARM-READ` loads the date window. The `EVALUATE`-`WHEN`
  style for status decode appears here for the first time.
- Mainline `PERFORM UNTIL END-OF-FILE`. For each transaction it
  filters by `TRAN-PROC-TS(1:10)` against the window, otherwise
  `NEXT SENTENCE`. On a `TRAN-CARD-NUM` change it emits an account
  total via `1120-WRITE-ACCOUNT-TOTALS` and re-fetches the xref.
- `1100-WRITE-TRANSACTION-REPORT` prints page totals every
  `WS-PAGE-SIZE = 20` lines using
  `IF FUNCTION MOD(WS-LINE-COUNTER, WS-PAGE-SIZE) = 0`, then writes
  headers and the detail row.
- `1120-WRITE-DETAIL` builds `TRANSACTION-DETAIL-REPORT` via discrete
  field `MOVE`s and writes one 133-byte line.
- Six CLOSE paragraphs; grand total appears once at EOF.

**Notable COBOL patterns.**
- `EVALUATE TRANFILE-STATUS WHEN '00' / WHEN '10' / WHEN OTHER` instead
  of nested `IF`s — first appearance in this catalog of the modern
  evaluate-style status check.
- `FUNCTION MOD(WS-LINE-COUNTER, WS-PAGE-SIZE)` — intrinsic-function
  use; equivalent to Java `lineCounter % pageSize`.
- `MOVE TRAN-TYPE-CD OF TRAN-RECORD TO FD-TRAN-TYPE-CD OF FD-TRAN-CAT-KEY`
  — qualified-name `OF` syntax to disambiguate identically-named fields
  in different copybooks.
- Edited picture clauses for currency: `PIC -ZZZ,ZZZ,ZZZ.ZZ` and
  `PIC +ZZZ,ZZZ,ZZZ.ZZ`. Java equivalent: `BigDecimal` with
  `DecimalFormat("+#,##0.00;-#,##0.00")` and right-aligned padding.

## `CBSTM03A` and `CBSTM03B` — Statement printer (multi-stage)

These two work as a pair. **All file I/O sits in `CBSTM03B`** behind a
single `CALL` interface; **`CBSTM03A` holds the business logic** and
formatting. The handoff is the linkage record `WS-M03B-AREA`:

```
05  WS-M03B-DD          PIC X(08).   * DD name (file selector)
05  WS-M03B-OPER        PIC X(01).   * 'O','C','R','K','W','Z'
05  WS-M03B-RC          PIC X(02).   * file status returned
05  WS-M03B-KEY         PIC X(25).   * key for keyed read
05  WS-M03B-KEY-LN      PIC S9(4).   * key length
05  WS-M03B-FLDT        PIC X(1000). * record buffer in/out
```

Each call from `CBSTM03A` populates `DD`, `OPER`, optionally `KEY`/
`KEY-LN`, then `CALL 'CBSTM03B' USING WS-M03B-AREA`. `CBSTM03B`
dispatches on `LK-M03B-DD` (one paragraph per file) and on
`LK-M03B-OPER` 88-levels (`M03B-OPEN`, `M03B-CLOSE`, `M03B-READ`,
`M03B-READ-K`, `M03B-WRITE`, `M03B-REWRITE`).

### `CBSTM03A` — statement creator (driver)

**Purpose.** For every card found in `XREFFILE`, fetch the customer and
account master, gather all transactions belonging to that card, and write
two outputs per account: a fixed-format text statement and a HTML
statement.

**Driving JCL.** `app/jcl/CREASTMT.JCL` step `STEP040`. Earlier steps
build a `(card-num, tran-id)`-keyed copy of the transaction master so
this program can read it sequentially.

**Key copybooks.**
- `COSTM01` — `TRNX-RECORD` with composite key
  `TRNX-CARD-NUM + TRNX-ID`.
- `CVACT03Y`, `CUSTREC` (note: not `CVCUS01Y`), `CVACT01Y`.

**Files / datasets.**
- `STMTFILE` — sequential FB 80, plain text statement.
- `HTMLFILE` — sequential FB 100, HTML statement.
- `TRNXFILE`, `XREFFILE`, `CUSTFILE`, `ACCTFILE` — accessed only via
  `CBSTM03B`.

**Programs called.**
- `CBSTM03B` — exclusive file gateway.
- `CEE3ABD`.

**Major paragraphs / control flow.**
- `PROCEDURE DIVISION` opens with **mainframe control-block walking**:
  `SET ADDRESS OF PSA-BLOCK TO PSAPTR.` then chains through the TCB and
  the TIOT to print every active DD name. This is z/OS-specific
  diagnostic code, not part of the business flow.
- After opening `STMTFILE`/`HTMLFILE`, the mainline drops into
  `0000-START` which uses `EVALUATE WS-FL-DD` plus
  **`ALTER 8100-FILE-OPEN TO PROCEED TO ...` + `GO TO`** to switch the
  body of `8100-FILE-OPEN` between the four file-specific OPEN
  paragraphs. This is one of the rarest legal COBOL-74 patterns — a
  self-modifying jump table — and is the specific feature the program
  was kept around to exercise.
- `8100-TRNXFILE-OPEN` opens `TRNXFILE` via `CBSTM03B`, reads the first
  row, then `GO TO 0000-START` again with `WS-FL-DD = 'READTRNX'`,
  which routes to `8500-READTRNX-READ` — the loop that fills the in-
  memory `WS-TRNX-TABLE` (`OCCURS 51 TIMES` of `OCCURS 10 TIMES`,
  i.e. up to 510 transactions across 51 cards).
- After the table is built, `8200-XREFFILE-OPEN` / `8300-CUSTFILE-OPEN`
  / `8400-ACCTFILE-OPEN` open the three lookup files (still through
  `CBSTM03B`) and finally `1000-MAINLINE` runs.
- `1000-MAINLINE` `PERFORM`s `1000-XREFFILE-GET-NEXT`, then for each
  card calls `2000-CUSTFILE-GET` (keyed read on `XREF-CUST-ID`),
  `3000-ACCTFILE-GET` (keyed read on `XREF-ACCT-ID`),
  `5000-CREATE-STATEMENT` (text + HTML headers via `5100/5200`), then
  `4000-TRNXFILE-GET` walks the in-memory table for the matching card
  number and emits one transaction line per row through
  `6000-WRITE-TRANS`.

**Notable COBOL patterns.**
- `ALTER 8100-FILE-OPEN TO PROCEED TO ...` + `GO TO` — explicitly listed
  in the program header as a deliberate exercise.
- Two-dimensional `OCCURS` table — `WS-CARD-TBL OCCURS 51 TIMES` with
  inner `WS-TRAN-TBL OCCURS 10 TIMES`. Java equivalent:
  `List<List<TrnxRow>>` or a flat `Map<String,List<TrnxRow>>`.
- `POINTER` fields with `SET ADDRESS OF ... TO PSAPTR` to walk
  z/OS control blocks (PSA → TCB → TIOT). No portable Java analog;
  treat as diagnostic-only and stub it out in any port.
- HTML lines via `STRING ... DELIMITED BY '*'` (using `'*'` as a
  no-match-everything terminator) so that the `STRING` verb behaves as
  concatenation.
- 88-level condition names used as **HTML literal pool**: each fixed
  HTML fragment is a `VALUE` of `HTML-FIXED-LN` and is "selected" via
  `SET HTML-Lxx TO TRUE` immediately before the `WRITE`. Be careful
  during transpilation — the variable is logically rewritten before
  every write.
- Edited `PIC Z(9).99-` for trailing-sign currency; HTML rendering
  reuses the edited string verbatim.

### `CBSTM03B` — file-handler subprogram

**Purpose.** Generic file I/O dispatcher — takes one `WS-M03B-AREA`
linkage record and translates it to OPEN / READ (sequential or keyed) /
CLOSE on one of four KSDS files. Returns the underlying VSAM file status
in `LK-M03B-RC`.

**Driving JCL.** None — invoked as a subroutine from `CBSTM03A`. The DD
names are bound by the parent step in `CREASTMT.JCL`.

**Key copybooks.** None; uses bare FDs.

**Files / datasets.** All KSDS:
- `TRNXFILE` — `INDEXED`, `SEQUENTIAL`, key `FD-TRNXS-ID`
  (composite `FD-TRNX-CARD + FD-TRNX-ID`, 32 bytes).
- `XREFFILE` — `INDEXED`, `SEQUENTIAL`, key `FD-XREF-CARD-NUM`.
- `CUSTFILE` — `INDEXED`, `RANDOM`, key `FD-CUST-ID PIC X(09)`.
- `ACCTFILE` — `INDEXED`, `RANDOM`, key `FD-ACCT-ID PIC 9(11)`.

**Programs called.** None.

**Major paragraphs / control flow.**
- `0000-START` `EVALUATE`s `LK-M03B-DD` and `PERFORM`s one of four
  per-file paragraphs (`1000-TRNXFILE-PROC`, `2000-XREFFILE-PROC`,
  `3000-CUSTFILE-PROC`, `4000-ACCTFILE-PROC`).
- Each per-file paragraph branches on the operation 88-levels
  (`M03B-OPEN`, `M03B-READ` or `M03B-READ-K`, `M03B-CLOSE`) using
  `IF ... GO TO 1900-EXIT END-IF`. For keyed reads the substring
  `LK-M03B-KEY (1:LK-M03B-KEY-LN)` is `MOVE`d into the FD's record
  key before `READ`.
- The exit paragraph `MOVE`s the appropriate file-status register into
  `LK-M03B-RC` so the caller sees it. `9999-GOBACK.` returns to the
  caller.

**Notable COBOL patterns.**
- `LINKAGE SECTION` with `PROCEDURE DIVISION USING LK-M03B-AREA` —
  textbook subprogram interface.
- "Switch table via `GO TO` + numbered exits" — `GO TO 1900-EXIT` /
  `GO TO 2900-EXIT` etc. Modern COBOL would use `EVALUATE TRUE`, but
  this style is retained deliberately to demonstrate the older idiom.
- The fact that **only this subprogram has SELECTs / FDs for these
  files** means the calling program (`CBSTM03A`) has no FD bindings for
  them. A static analyzer that builds a file-by-program matrix has to
  follow `CALL` edges to attribute these files to `CBSTM03A`.

## `CBEXPORT` — Branch-migration export

**Purpose.** Read every record from each of the five master VSAM files
and emit a single multi-record export VSAM file (KSDS keyed on a
sequence counter). One COBOL record type per source file, distinguished
by a single-byte `EXPORT-REC-TYPE` discriminator: `'C'`ustomer,
`'A'`ccount, `'X'`ref, `'T'`ransaction, `'D'`-card.

**Driving JCL.** `app/jcl/CBEXPORT.jcl`. Step `STEP01` runs `IDCAMS` to
DELETE/DEFINE the export KSDS, then `STEP02` runs `CBEXPORT`.

**Key copybooks.**
- `CVCUS01Y`, `CVACT01Y`, `CVACT03Y`, `CVTRA05Y`, `CVACT02Y` — each FD
  layered with the matching business copybook.
- `CVEXPORT` — the multi-record export layout: a fixed
  `EXPORT-RECORD` header (1-byte type, 26-byte timestamp, 9-byte
  sequence, 4-byte branch, 5-byte region) followed by a 460-byte
  payload that is `REDEFINES`-ed into five alternative shapes
  (customer, account, transaction, xref, card). One on-disk record can
  represent any of those five — readers must dispatch on the type
  byte. Storage uses mixed `COMP` / `COMP-3` for the numeric fields.

**Files / datasets.**
- Inputs: `CUSTFILE`, `ACCTFILE`, `XREFFILE`, `TRANSACT`, `CARDFILE` —
  all KSDS, all opened `INPUT`, all read `SEQUENTIAL`.
- Output: `EXPFILE` — KSDS, `RECORDING MODE F`, `RECORD CONTAINS 500`,
  keyed by `EXPORT-SEQUENCE-NUM`. Defined inline by IDCAMS before this
  step runs.

**Programs called.**
- `CEE3ABD`.

**Major paragraphs / control flow.**
- `0000-MAIN-PROCESSING` is a flat sequence:
  `1000-INITIALIZE` → `2000-EXPORT-CUSTOMERS` →
  `3000-EXPORT-ACCOUNTS` → `4000-EXPORT-XREFS` →
  `5000-EXPORT-TRANSACTIONS` → `5500-EXPORT-CARDS` →
  `6000-FINALIZE` → `GOBACK`.
- `1050-GENERATE-TIMESTAMP` builds a 26-byte timestamp via
  `ACCEPT FROM DATE YYYYMMDD` and `ACCEPT FROM TIME` plus
  `STRING`-based formatting.
- Each of the five `Nxxx-EXPORT-...` paragraphs follows the same
  pattern: prime read; `PERFORM UNTIL <eof>`; `Nxxx-CREATE-..-EXP-REC`
  to map the source fields into the appropriate `EXP-...` redefinition
  and `WRITE EXPORT-OUTPUT-RECORD FROM EXPORT-RECORD`; bump the
  per-type and total counters.
- `6000-FINALIZE` closes everything and `DISPLAY`s a summary block.

**Notable COBOL patterns.**
- 88-level **EOF flags** with `VALUE '10'` (e.g. `WS-CUSTOMER-EOF`)
  used directly in `PERFORM UNTIL WS-CUSTOMER-EOF` — concise idiom that
  hides the AT END / file-status check.
- "Single-buffer multi-record" file design via `REDEFINES`. The same
  bytes are interpreted differently per record type. **`COMP` and
  `COMP-3` packing** is used aggressively in `CVEXPORT` (e.g.
  `EXP-CUST-ID PIC 9(09) COMP`, `EXP-ACCT-CURR-BAL PIC S9(10)V99 COMP-3`)
  — note this means the export bytes are **binary**, not zoned; a
  reader implementing this in Java must use packed-decimal decoding
  for COMP-3 and `int`/`long` for `COMP`/`BINARY`.
- The export VSAM key is `EXPORT-SEQUENCE-NUM PIC 9(9) COMP`, populated
  monotonically via `WS-SEQUENCE-COUNTER` — a synthetic surrogate key.

## `CBIMPORT` — Branch-migration import

**Purpose.** Inverse of `CBEXPORT`: read the multi-record `EXPFILE` and
fan it out to five normalized **sequential** output files
(customer / account / xref / transaction / card), one record-type per
file, with an additional error file for unknown / un-handled record
types.

**Driving JCL.** `app/jcl/CBIMPORT.jcl` (single step `STEP01`). The
output PS files are NEW/CATLG and sized by JCL.

**Key copybooks.**
- `CVEXPORT` — the same multi-record layout `CBEXPORT` writes; the FD
  for the input file is plain `EXPORT-INPUT-RECORD PIC X(500)` and the
  export shape is mapped onto it via `READ ... INTO EXPORT-RECORD`.
- `CVCUS01Y`, `CVACT01Y`, `CVACT03Y`, `CVTRA05Y`, `CVACT02Y` — used
  directly as the FDs of the five output files.

**Files / datasets.**
- `EXPFILE` — KSDS (or PS) input; FD declares `RECORDING MODE F`,
  `RECORD CONTAINS 500`.
- `CUSTOUT` (LRECL 500), `ACCTOUT` (300), `XREFOUT` (50),
  `TRNXOUT` (350), `CARDOUT` (150) — all sequential FB.
- `ERROUT` — sequential FB 132, pipe-delimited error log.

**Programs called.**
- `CEE3ABD`.

**Major paragraphs / control flow.**
- `0000-MAIN-PROCESSING`: `1000-INITIALIZE` (build current
  date/time strings via `FUNCTION CURRENT-DATE(1:4)` etc., open all
  files), `2000-PROCESS-EXPORT-FILE`, `3000-VALIDATE-IMPORT`
  (placeholder), `4000-FINALIZE`.
- `2000` does prime-read + EOF loop, calling `2200-PROCESS-RECORD-BY-TYPE`
  for every input record.
- `2200` `EVALUATE EXPORT-REC-TYPE WHEN 'C' / 'A' / 'X' / 'T' / 'D' /
  OTHER` and dispatches to `2300/2400/2500/2600/2650`. Each handler
  `INITIALIZE`s the destination record, copies fields from the
  appropriate `EXP-...` redefinition, `WRITE`s, and bumps a counter.
- `WHEN OTHER` → `2700-PROCESS-UNKNOWN-RECORD` builds a 132-byte
  pipe-delimited error log line and calls `2750-WRITE-ERROR`.
- `4000-FINALIZE` closes every file and displays per-type counts.

**Notable COBOL patterns.**
- `READ EXPORT-INPUT INTO EXPORT-RECORD` — the `INTO` clause copies
  the file buffer into the working-storage layout, decoupling the
  record buffer from the in-memory copy.
- Date construction by `MOVE FUNCTION CURRENT-DATE(1:4) TO ...`
  followed by literal `'-'` MOVEs into specific substring positions
  — a verbose alternative to the `STRING` form used in `CBEXPORT`.
- The `2300-PROCESS-CUSTOMER-RECORD` etc. handlers are pure
  field-by-field MOVE blocks. A transpiler can express each as a
  static mapping function `Map<ExportRecord, CustomerRecord>`.
- `3000-VALIDATE-IMPORT` is a stub that always reports success — leave
  a TODO marker if porting.

## `CSUTLDTC` — Date conversion / validation utility

**Purpose.** Wrapper around the LE callable service `CEEDAYS`
(date → Lillian-day) used purely for **validation**: the program ignores
the Lillian output and returns a human-readable English message in
`LS-RESULT` plus the LE severity in `RETURN-CODE` so the caller can decide
whether the supplied date is valid.

**Driving JCL.** None — invoked as a subroutine from both batch
(`CBTRN02C`-style validation flows are not present, but `CORPT00C`,
`COTRN02C` and `CSUTLDPY` use it; see the call site at
`app/cpy/CSUTLDPY.cpy:293`) and online programs.

**Key copybooks.** None (this program is a copybook substitute itself —
its `CSUTLDPY` companion is the typical caller).

**Files / datasets.** None.

**Programs called.**
- `CEEDAYS` — IBM Language Environment date callable service.

**Major paragraphs / control flow.**
- `PROCEDURE DIVISION USING LS-DATE, LS-DATE-FORMAT, LS-RESULT`.
- Initialize `WS-MESSAGE`; `PERFORM A000-MAIN THRU A000-MAIN-EXIT`.
- `A000-MAIN` builds two **VSTRING** pairs (length + text) — these
  are LE's preferred string carrier — `MOVE`s the input date and
  format mask into them, zeroes the Lillian output, and calls
  `CEEDAYS USING WS-DATE-TO-TEST, WS-DATE-FORMAT, OUTPUT-LILLIAN,
  FEEDBACK-CODE`.
- `EVALUATE TRUE` over a series of 88-level condition names on the
  16-byte feedback token (`FC-INVALID-DATE`, `FC-BAD-DATE-VALUE`,
  `FC-INVALID-MONTH`, etc., each defined with a hex
  `VALUE X'...'`) maps the LE feedback code to one of ten
  English `WS-RESULT` strings.
- Returns `WS-MESSAGE` (severity + msg-no + result + tested date + mask)
  in `LS-RESULT` and the severity in `RETURN-CODE`. Exits with
  `EXIT PROGRAM`.

**Notable COBOL patterns.**
- **Variable-length string** `(VString)` layout —
  `Vstring-length PIC S9(4) BINARY` followed by `Vstring-text` whose
  inner `Vstring-char OCCURS 0 TO 256 TIMES DEPENDING ON
  Vstring-length` — the canonical LE `vstring_t` shape.
- `88`-level conditions with `VALUE X'...'` 8-byte hex literals to
  pattern-match the LE feedback token — a transpiler that ports this
  to Java should use a `switch (feedbackCode)` over the same hex
  constants (or, better, replace the whole call with
  `LocalDate.parse(...)` and translate exceptions back to severity
  codes).
- `EXIT PROGRAM` (rather than `GOBACK`) — semantically identical here
  but explicitly used to mark this as a dynamic-CALLed subprogram.
- Note the related but separate assembler routine `COBDATFT`
  (`app/asm/COBDATFT.asm`), called by `CBACT01C`, that
  reformats `YYYYMMDD` ↔ `YYYY-MM-DD` with no validation. The two
  utilities are complementary: `CSUTLDTC` validates, `COBDATFT`
  reformats.

## Cross-cutting notes for transpilers / analyzers

**File status decoder.** Every program in this catalog ships a copy of
`9910-DISPLAY-IO-STATUS` (sometimes renamed `Z-DISPLAY-IO-STATUS`).
The interesting branch handles VSAM extended status codes (status
`'9x'`), where the second byte is a **binary integer**, not ASCII.
The program `REDEFINES` a `PIC 9(4) BINARY` over two `PIC X` bytes to
print it. A transpiler must preserve this binary semantics.

**APPL-RESULT result code.** The `S9(9) COMP` `APPL-RESULT` field with
`88 APPL-AOK VALUE 0 / 88 APPL-EOF VALUE 16` is the **internal** error
domain. It is set from file status; downstream logic only checks the
88-level conditions. Java equivalent: a small `enum` with `OK`, `EOF`,
`ERROR` and a translation function from a two-byte file status.

**`Z-GET-DB2-FORMAT-TIMESTAMP`.** Both `CBACT04C` and `CBTRN02C` carry
the same paragraph that emits a 26-byte
`YYYY-MM-DD-HH.MI.SS.MIL0000` literal from `FUNCTION CURRENT-DATE`
into a `REDEFINES` of a 26-byte buffer. Refactor candidate: extract
into a common copybook for the migration.

**Abend strategy.** Every program calls `CEE3ABD` with an abend code of
**999** and timing 0. There is no graceful recovery and no rollback —
once a file-status check fails, the program crashes the JCL step and
the surrounding job's COND check (`COND=(0,NE)` etc., visible in
`CREASTMT.JCL`) prevents downstream steps from running. Java port
should map this to a checked exception that propagates to the job
runner, not a silent return.

**Multiple programs share the same files**. `ACCTFILE`, `CARDFILE`,
`XREFFILE`, `TRANFILE`, `CUSTFILE`, `TCATBALF` are read by half a dozen
programs each, with **different** access modes (`SEQUENTIAL` vs
`RANDOM` vs `I-O`) and sometimes with different alternate-key
declarations. A unified Java DAO layer must accept the union of all
access patterns or expose each as a separate adapter.

**Shared copybooks (quick reference).**

| Copybook | 01-level | RECLN | Used by batch programs |
|---|---|---|---|
| `CVACT01Y` | `ACCOUNT-RECORD` | 300 | `CBACT01C`, `CBACT04C`, `CBTRN01C`, `CBTRN02C`, `CBSTM03A`, `CBEXPORT`, `CBIMPORT` |
| `CVACT02Y` | `CARD-RECORD` | 150 | `CBACT02C`, `CBTRN01C`, `CBEXPORT`, `CBIMPORT` |
| `CVACT03Y` | `CARD-XREF-RECORD` | 50 | `CBACT03C`, `CBACT04C`, `CBTRN01C`, `CBTRN02C`, `CBTRN03C`, `CBSTM03A`, `CBEXPORT`, `CBIMPORT` |
| `CVCUS01Y` | `CUSTOMER-RECORD` | 500 | `CBCUS01C`, `CBTRN01C`, `CBEXPORT`, `CBIMPORT` |
| `CUSTREC` | `CUSTOMER-RECORD` (variant) | 500 | `CBSTM03A` only — note the `CUST-DOB-YYYYMMDD` field name vs `CUST-DOB-YYYY-MM-DD` in `CVCUS01Y` |
| `CVTRA01Y` | `TRAN-CAT-BAL-RECORD` | 50 | `CBACT04C`, `CBTRN02C` |
| `CVTRA02Y` | `DIS-GROUP-RECORD` | 50 | `CBACT04C` |
| `CVTRA03Y` | `TRAN-TYPE-RECORD` | 60 | `CBTRN03C` |
| `CVTRA04Y` | `TRAN-CAT-RECORD` | 60 | `CBTRN03C` |
| `CVTRA05Y` | `TRAN-RECORD` | 350 | `CBACT04C`, `CBTRN01C`, `CBTRN02C`, `CBTRN03C`, `CBEXPORT`, `CBIMPORT` |
| `CVTRA06Y` | `DALYTRAN-RECORD` | 350 | `CBTRN01C`, `CBTRN02C` |
| `CVTRA07Y` | report layouts | n/a | `CBTRN03C` only |
| `COSTM01` | `TRNX-RECORD` | 350 | `CBSTM03A` only |
| `CVEXPORT` | `EXPORT-RECORD` | 500 | `CBEXPORT`, `CBIMPORT` |
| `CODATECN` | `CODATECN-REC` (in/out for `COBDATFT`) | n/a | `CBACT01C` |
