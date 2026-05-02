# Extension Module: `app-transaction-type-db2`

A COBOL+DB2 deep dive for Java developers.

This document describes everything under `app/app-transaction-type-db2/`. The module is an *optional* CardDemo extension that re-implements transaction-type reference-data maintenance on top of DB2 (static embedded SQL) instead of the core application's VSAM `TRANTYPE` file. It is the only module in this repository that uses embedded SQL, so it is also the canonical reference for how DB2 host variables, DCLGEN copybooks, cursor processing, and CICS-DB2 attachment are wired together in CardDemo.

---

## 1. Module overview

Two screens (`CTLI` list/update/delete, `CTTU` add/edit) and one batch driver (`COBTUPDT`) maintain two related DB2 tables in schema `CARDDEMO`:

- `CARDDEMO.TRANSACTION_TYPE` (parent, 7 rows seeded — `01..07`).
- `CARDDEMO.TRANSACTION_TYPE_CATEGORY` (child, 18 rows seeded — `(type, category)` pairs), linked by a `FOREIGN KEY ... ON DELETE RESTRICT`.

DB2 is used (instead of the core's VSAM KSDS `TRANTYPE`) to demonstrate:

- Static embedded SQL with `:host-variable` parameter passing.
- DCLGEN copybooks for `INCLUDE`-style table declarations.
- Forward and backward cursors driven by paged-screen navigation.
- CRUD with `SQLCODE` checking and `EXEC CICS SYNCPOINT` for unit-of-work commit.
- The DB2/CICS attachment via a `DB2ENTRY` with two `DB2TRAN` resources.
- A `DSNTIAUL` extract job (`TRANEXTR`) that re-materialises the DB2 data as the flat `TRANTYPE.PS` file the core CardDemo batch consumes — i.e. DB2 is the system of record for *administration*, VSAM-equivalent flat file is the format the core *reads*.

Transaction codes added to the CICS region:

| Tran ID | Program    | Mapset    | Function                                            |
|:--------|:-----------|:----------|:----------------------------------------------------|
| `CTLI`  | `COTRTLIC` | `COTRTLI` | List / inline-update / delete (admin menu option 5) |
| `CTTU`  | `COTRTUPC` | `COTRTUP` | Add / edit single record       (admin menu option 6) |

In the admin menu (`CA00` / `COADM01C`), options 5 and 6 `XCTL` to these programs.

---

## 2. Database schema

### 2.1 `CARDDEMO.TRANSACTION_TYPE`

Source: `app/app-transaction-type-db2/ddl/TRNTYPE.ddl` and the more complete `ctl/DB2CREAT.ctl` (which also defines tablespace, grants, and unique index).

```sql
CREATE TABLE CARDDEMO.TRANSACTION_TYPE
(   TR_TYPE                        CHAR(2)     NOT NULL,
    TR_DESCRIPTION                 VARCHAR(50) NOT NULL,
    PRIMARY KEY(TR_TYPE));
```

| Column           | Type          | Notes                                  |
|:-----------------|:--------------|:---------------------------------------|
| `TR_TYPE`        | `CHAR(2)`     | Primary key. Numeric in the data ('01'..'07') but stored as `CHAR` so it pads with spaces. |
| `TR_DESCRIPTION` | `VARCHAR(50)` | NOT NULL. `VARCHAR` => DCLGEN exposes a 2-byte length + 50-byte text pair (see below). |

Index from `ddl/XTRNTYPE.ddl`:

```sql
CREATE UNIQUE INDEX CARDDEMO.XTRAN_TYPE
    ON CARDDEMO.TRANSACTION_TYPE (TR_TYPE ASC) ERASE NO CLOSE NO;
```

Tablespace `CARDDEMO.CARDSPC1` (`SEGSIZE 4`, `LOCKSIZE TABLE`, `BUFFERPOOL BP0`, `CCSID EBCDIC`) is created in `ctl/DB2CREAT.ctl`.

### 2.2 `CARDDEMO.TRANSACTION_TYPE_CATEGORY`

Source: `app/app-transaction-type-db2/ddl/TRNTYCAT.ddl` (and re-stated in `ctl/DB2CREAT.ctl`):

```sql
CREATE TABLE CARDDEMO.TRANSACTION_TYPE_CATEGORY
(   TRC_TYPE_CODE     CHAR(2)     NOT NULL,
    TRC_TYPE_CATEGORY CHAR(4)     NOT NULL,
    TRC_CAT_DATA      VARCHAR(50) NOT NULL,
    PRIMARY KEY(TRC_TYPE_CODE, TRC_TYPE_CATEGORY),
    FOREIGN KEY TRC_TYPE_CODE (TRC_TYPE_CODE)
        REFERENCES CARDDEMO.TRANSACTION_TYPE (TR_TYPE) ON DELETE RESTRICT);
```

| Column              | Type          | Notes                                |
|:--------------------|:--------------|:-------------------------------------|
| `TRC_TYPE_CODE`     | `CHAR(2)`     | PK part 1. FK to `TRANSACTION_TYPE.TR_TYPE`. |
| `TRC_TYPE_CATEGORY` | `CHAR(4)`     | PK part 2.                           |
| `TRC_CAT_DATA`      | `VARCHAR(50)` | NOT NULL.                            |

`ON DELETE RESTRICT` is the reason `COTRTUPC` and `COTRTLIC` test `SQLCODE = -532` after a `DELETE FROM TRANSACTION_TYPE` and surface "Please delete associated child records first" to the user.

Index from `ddl/XTRNTYCAT.ddl`:

```sql
CREATE UNIQUE INDEX CARDDEMO.X_TRAN_TYPE_CATG
    ON CARDDEMO.TRANSACTION_TYPE_CATEGORY (TRC_TYPE_CODE ASC, TRC_TYPE_CATEGORY ASC)
    ERASE NO CLOSE NO;
```

Lives in tablespace `CARDDEMO.CARDSTTC` (also created by `DB2CREAT.ctl`).

### 2.3 DCLGEN host-variable layouts

DCLGEN copybooks live in `app/app-transaction-type-db2/dcl/`. They serve two purposes simultaneously: they emit a `DECLARE TABLE` (for the precompiler to validate column names) **and** an `01` group with COBOL host variables matching the columns one-to-one.

`dcl/DCLTRTYP.dcl`:

```cobol
EXEC SQL DECLARE CARDDEMO.TRANSACTION_TYPE TABLE
( TR_TYPE          CHAR(2)     NOT NULL,
  TR_DESCRIPTION   VARCHAR(50) NOT NULL ) END-EXEC.

01  DCLTRANSACTION-TYPE.
    10 DCL-TR-TYPE             PIC X(2).
    10 DCL-TR-DESCRIPTION.
       49 DCL-TR-DESCRIPTION-LEN  PIC S9(4) USAGE COMP.
       49 DCL-TR-DESCRIPTION-TEXT PIC X(50).
```

The `49`-level pair is the standard DB2 `VARCHAR` host-variable shape: a binary `S9(4) COMP` (think Java `short`) holding the length, followed by a fixed `X(50)` buffer. When you `MOVE` *into* `DCL-TR-DESCRIPTION` for an `INSERT`/`UPDATE`, you must set both `DCL-TR-DESCRIPTION-LEN` and `DCL-TR-DESCRIPTION-TEXT`. When DB2 `FETCH`/`SELECT INTO` populates it, only the first `LEN` bytes of `TEXT` are meaningful — the rest may be garbage. `COTRTUPC.cbl` line 15240 demonstrates the safe read pattern:

```cobol
MOVE DCL-TR-DESCRIPTION-TEXT(1: DCL-TR-DESCRIPTION-LEN)
                         TO TTUP-OLD-TTYP-TYPE-DESC
```

`dcl/DCLTRCAT.dcl` follows the same pattern for the child table:

```cobol
01  DCLTRANSACTION-TYPE-CATEGORY.
    10 DCL-TRC-TYPE-CODE       PIC X(2).
    10 DCL-TRC-TYPE-CATEGORY   PIC X(4).
    10 DCL-TRC-CAT-DATA.
       49 DCL-TRC-CAT-DATA-LEN  PIC S9(4) USAGE COMP.
       49 DCL-TRC-CAT-DATA-TEXT PIC X(50).
```

Java mental model: think `record TransactionType(String trType, String trDescription)` where the precompiler injects a `PreparedStatement` for every `EXEC SQL` block and the host variables are positional bind/result columns.

Which programs `INCLUDE` which DCLGEN:

| Program     | `EXEC SQL INCLUDE DCLTRTYP` | `EXEC SQL INCLUDE DCLTRCAT` |
|:------------|:----------------------------|:----------------------------|
| `COTRTUPC`  | yes                         | yes (declared but only `TRANSACTION_TYPE` is queried — the `DCLTRCAT` declare is informational) |
| `COTRTLIC`  | yes                         | no                          |
| `COBTUPDT`  | yes                         | no                          |

---

## 3. CICS resources added

From `csd/CRDDEMOD.csd`:

| Resource type | Name       | Group     | Key attributes                                   |
|:--------------|:-----------|:----------|:-------------------------------------------------|
| `MAPSET`      | `COTRTLI`  | `CARDDEMO`| Tran-type listing screen                          |
| `MAPSET`      | `COTRTUP`  | `CARDDEMO`| Tran-type maintenance screen                      |
| `PROGRAM`     | `COTRTLIC` | `CARDDEMO`| `LANGUAGE(COBOL) CONCURRENCY(QUASIRENT) EXECKEY(USER)` |
| `PROGRAM`     | `COTRTUPC` | `CARDDEMO`| `LANGUAGE(COBOL) CONCURRENCY(QUASIRENT) EXECKEY(USER)` |
| `TRANSACTION` | `CTLI`     | `CARDDEMO`| `PROGRAM(COTRTLIC) ACTION(BACKOUT) ISOLATE(YES)` |
| `TRANSACTION` | `CTTU`     | `CARDDEMO`| `PROGRAM(COTRTUPC) ACTION(BACKOUT) ISOLATE(YES)` |
| `DB2ENTRY`    | `CARDDEMO` | `CARDDEMO`| `PLAN(CARDDEMO) THREADLIMIT(1) THREADWAIT(YES) DROLLBACK(YES) AUTHTYPE(USERID)` |
| `DB2TRAN`     | `CTLITRAN` | `CARDDEMO`| `ENTRY(CARDDEMO) TRANSID(CTLI)` — routes `CTLI` to the `CARDDEMO` plan |
| `DB2TRAN`     | `CTTUTRAN` | `CARDDEMO`| `ENTRY(CARDDEMO) TRANSID(CTTU)` — routes `CTTU` to the `CARDDEMO` plan |

The `DB2ENTRY`/`DB2TRAN` pair is the CICS-DB2 attachment glue. It tells the CICS DB2 attachment facility that whenever a task running tran `CTLI` or `CTTU` issues an `EXEC SQL`, it should acquire a thread bound to the `CARDDEMO` plan. `ACTION(BACKOUT)` plus `DROLLBACK(YES)` means an abend rolls back DB2 work along with CICS-managed resources. There is no MQ connection in this module.

---

## 4. COBOL programs

### 4.1 `COTRTUPC` — single-record add/update/delete (online)

File: `app/app-transaction-type-db2/cbl/COTRTUPC.cbl` (1702 lines).

**Purpose.** Pseudo-conversational CICS program that lets an admin look up one transaction type by 2-digit code, then add it (if missing), edit its description, or delete it (with confirmation).

**Online.** Driven by CICS transaction `CTTU` (`LIT-THISTRANID`). Reachable from the admin menu (`COADM01C`/`CA00`) or from `COTRTLIC` via `XCTL`. Pseudo-conversational: each user keystroke ends with `EXEC CICS RETURN TRANSID('CTTU') COMMAREA(...)`, and on the next call the program rehydrates state from `DFHCOMMAREA`.

**BMS map(s).** `COTRTUP` mapset, map `CTRTUPA` (`bms/COTRTUP.bms` → symbolic copybook `cpy-bms/COTRTUP.cpy`). Significant fields:

| Field name | Length | Role                                  |
|:-----------|-------:|:--------------------------------------|
| `TRTYPCD`  |   2    | Transaction type code (PK input)      |
| `TRTYDSC`  |  50    | Transaction description               |
| `TRNNAME`  |   4    | Echoed transaction id                 |
| `PGMNAME`  |   8    | Echoed program name                   |
| `TITLE01/02`| 40    | Title lines from `COTTL01Y`           |
| `CURDATE`/`CURTIME` | 8 | Filled from `FUNCTION CURRENT-DATE` |
| `INFOMSG`  |  45    | Centred user-action prompt            |
| `ERRMSG`   |  78    | Red error line                        |
| `FKEYS/FKEY04/FKEY05/FKEY06/FKEY12` | varies | PF-key labels, dynamically set NORM/DRK to gate which keys are live |

**Key copybooks.**

- `CSUTLDWY` — generic date edit working storage.
- `COCOM01Y` — application commarea (`CARDDEMO-COMMAREA`, `CDEMO-FROM-PROGRAM`, etc.).
- `COTTL01Y` — screen titles `CCDA-TITLE01`, `CCDA-TITLE02`.
- `COTRTUP` — BMS symbolic map (`CTRTUPAI`/`CTRTUPAO`).
- `CSDAT01Y` — current-date helpers.
- `CSMSG01Y`, `CSMSG02Y` — generic + abend message structures.
- `CSUSR01Y` — signed-on user data.
- `CSSETATY` — copy-replacing template that stamps screen attributes for one field (used at line 13580 with `(TESTVAR1)→DESCRIPTION`, `(SCRNVAR2)→TRTYDSC`, `(MAPNAME3)→CTRTUPA`).
- `CSSTRPFY` — common `YYYY-STORE-PFKEY` / `YYYY-STORE-PFKEY-EXIT` paragraphs (PF-key normalisation).
- `DFHBMSCA`, `DFHAID` — IBM-supplied BMS attribute and AID-key constants.
- DB2: `EXEC SQL INCLUDE SQLCA`, `EXEC SQL INCLUDE DCLTRTYP`, `EXEC SQL INCLUDE DCLTRCAT`.

**DB2 SQL surface.** All static embedded SQL on `CARDDEMO.TRANSACTION_TYPE`. No cursors.

| Paragraph                  | Statement                                             | Purpose                                       |
|:---------------------------|:------------------------------------------------------|:----------------------------------------------|
| `9100-GET-TRANSACTION-TYPE`| `SELECT TR_TYPE, TR_DESCRIPTION INTO :DCL-TR-TYPE, :DCL-TR-DESCRIPTION FROM CARDDEMO.TRANSACTION_TYPE WHERE TR_TYPE = :DCL-TR-TYPE` | Read by PK to populate the screen. Treats `SQLCODE = +100` as "not found" and surfaces the prompt to add. |
| `9600-WRITE-PROCESSING`    | `UPDATE CARDDEMO.TRANSACTION_TYPE SET TR_DESCRIPTION = :DCL-TR-DESCRIPTION WHERE TR_TYPE = :DCL-TR-TYPE` | Save edits. `SQLCODE = +100` → falls through to `9700-INSERT-RECORD` (auto-promote update→insert). `SQLCODE = -911` → "Could not lock record" (deadlock/timeout). |
| `9700-INSERT-RECORD`       | `INSERT INTO CARDDEMO.TRANSACTION_TYPE (TR_TYPE, TR_DESCRIPTION) VALUES (:DCL-TR-TYPE, :DCL-TR-DESCRIPTION)` | Create new record. Issues `EXEC CICS SYNCPOINT` on success. |
| `9800-DELETE-PROCESSING`   | `DELETE FROM CARDDEMO.TRANSACTION_TYPE WHERE TR_TYPE = :DCL-TR-TYPE` | Remove record. `SQLCODE = -532` (FK violation) → message "Please delete associated child records first". |

Each successful mutating statement is committed with `EXEC CICS SYNCPOINT END-EXEC` — the CICS unit-of-work commit also commits the DB2 thread.

**CICS commands used.**

| Command                                  | Where it appears                       |
|:-----------------------------------------|:---------------------------------------|
| `EXEC CICS HANDLE ABEND LABEL(ABEND-ROUTINE)` | top of `0000-MAIN`                |
| `EXEC CICS RECEIVE MAP(...) MAPSET(...) INTO(CTRTUPAI) RESP/RESP2` | `1100-RECEIVE-MAP` |
| `EXEC CICS SEND MAP(...) MAPSET(...) FROM(CTRTUPAO) CURSOR ERASE FREEKB RESP` | `3400-SEND-SCREEN` |
| `EXEC CICS SEND FROM(ABEND-DATA) ... NOHANDLE ERASE` | `ABEND-ROUTINE` |
| `EXEC CICS XCTL PROGRAM(...) COMMAREA(CARDDEMO-COMMAREA)` | PF03 exit, returns control to caller |
| `EXEC CICS SYNCPOINT`                    | After each successful `INSERT`/`UPDATE`/`DELETE`, also before exit-`XCTL` |
| `EXEC CICS RETURN TRANSID('CTTU') COMMAREA(WS-COMMAREA) LENGTH(...)` | `COMMON-RETURN`, ends each pseudo-conversation |
| `EXEC CICS HANDLE ABEND CANCEL` / `EXEC CICS ABEND ABCODE(...)` | inside `ABEND-ROUTINE` |

**Major paragraphs / control flow.**

- `0000-MAIN` — restore/initialise commarea (treats `EIBCALEN = 0` as "first entry from CICS", anything else as a reentry whose first `LENGTH OF CARDDEMO-COMMAREA` bytes are the application commarea and the rest is `WS-THIS-PROGCOMMAREA`), run `0001-CHECK-PFKEYS`, then a giant `EVALUATE TRUE` dispatcher keyed on AID key + state machine flag `TTUP-CHANGE-ACTION` (88-levels `TTUP-DETAILS-NOT-FETCHED`, `TTUP-SHOW-DETAILS`, `TTUP-CONFIRM-DELETE`, `TTUP-CHANGES-OK-NOT-CONFIRMED`, `TTUP-CREATE-NEW-RECORD`, `TTUP-DELETE-DONE`, `TTUP-CHANGES-OKAYED-AND-DONE`, ...). All branches fall through to `COMMON-RETURN`.
- `1000-PROCESS-INPUTS` — `1100-RECEIVE-MAP`, then `1150-STORE-MAP-IN-NEW` (move the entered values into `TTUP-NEW-DETAILS`), then `1200-EDIT-MAP-INPUTS` (calls `1210-EDIT-TRANTYPE`, `1230-EDIT-ALPHANUM-REQD`, `1245-EDIT-NUM-REQD`, and `1205-COMPARE-OLD-NEW` to set `NO-CHANGES-FOUND` vs `CHANGE-HAS-OCCURRED`). Errors land in `WS-RETURN-MSG` with `INPUT-ERROR` set.
- `2000-DECIDE-ACTION` — second-level state machine that, given valid inputs, picks the next action: `WHEN TTUP-DETAILS-NOT-FETCHED` triggers `9000-READ-TRANTYPE`, `WHEN TTUP-CONFIRM-DELETE AND CCARD-AID-PFK12` cancels the delete, `WHEN TTUP-SHOW-DETAILS` checks for clean inputs and promotes to `TTUP-CHANGES-OK-NOT-CONFIRMED` to ask for save confirmation, etc.
- `3000-SEND-MAP` — five-step orchestrator: `3100-SCREEN-INIT` (titles, current date/time), `3200-SETUP-SCREEN-VARS` (one of `3201-SHOW-INITIAL-VALUES`, `3202-SHOW-ORIGINAL-VALUES`, or `3203-SHOW-UPDATED-VALUES` depending on state), `3250-SETUP-INFOMSG` (centre-justify the prompt text), `3300-SETUP-SCREEN-ATTRS` (`3310-PROTECT-ALL-ATTRS` then context-driven `3320-UNPROTECT-FEW-ATTRS`, plus cursor positioning `MOVE -1 TO ...L`), `3390-SETUP-INFOMSG-ATTRS`, `3391-SETUP-PFKEY-ATTRS` (re-colour PF labels NORM/DRK based on which keys are live), then `3400-SEND-SCREEN` issues the actual `SEND MAP`.
- `9000-READ-TRANTYPE` → `9100-GET-TRANSACTION-TYPE` (the `SELECT INTO`) → `9500-STORE-FETCHED-DATA` (copy `DCL-TR-DESCRIPTION-TEXT(1: DCL-TR-DESCRIPTION-LEN)` into the screen's "old" buffer) — the PK lookup chain.
- `9600-WRITE-PROCESSING` / `9700-INSERT-RECORD` / `9800-DELETE-PROCESSING` — the three mutating SQL paragraphs. Each issues `EXEC CICS SYNCPOINT` on `SQLCODE = 0`. `9600` automatically chains to `9700` if the `UPDATE` returns `+100` (no-such-row), so the CTTU "save" button is really an upsert.
- `ABEND-ROUTINE` — the `LABEL(...)` target of `HANDLE ABEND`; sends `ABEND-DATA` to the screen, cancels the abend handler with `HANDLE ABEND CANCEL`, then re-issues `EXEC CICS ABEND ABCODE('9999')` to terminate the task and trigger `ACTION(BACKOUT)` rollback of any uncommitted DB2 work.

**Notable patterns.**

- `EXEC SQL INCLUDE SQLCA` brings the `SQLCA` block into working storage; `SQLCODE`, `SQLERRM` are then directly addressable.
- No `WHENEVER` directive — every SQL block is followed by an explicit `EVALUATE TRUE` over `SQLCODE` (defensive style; you never have non-local control transfer to surprise you).
- Host variables use the `:identifier` colon-prefixed syntax inside `EXEC SQL`.
- Error display uses `MOVE SQLCODE TO WS-DISP-SQLCODE` (a `PIC ----9` edited field) so the value lands in `WS-RETURN-MSG` formatted, then is sent back via `ERRMSGO`.
- The state machine in `TTUP-CHANGE-ACTION` (single `PIC X(1)` with a forest of 88-levels) is essentially a Java `enum` of UI states and would map cleanly to one in a port.

### 4.2 `COTRTLIC` — paged list with inline update / delete (online)

File: `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` (2098 lines).

**Purpose.** Paged listing screen showing 7 transaction types per page, with optional type-code and description filters. Each row has a select column that lets the user pick `U` to inline-update its description or `D` to delete it. Forward and backward paging are implemented with two opposite-direction cursors.

**Online.** CICS transaction `CTLI`. Pseudo-conversational. Entered from admin menu option 5 or by `XCTL` from `COTRTUPC`. Can `XCTL` to `COTRTUPC` (`CTTU`) on PF02 to create a new record.

**BMS map.** `COTRTLI` mapset, map `CTRTLIA`. The map (`bms/COTRTLI.bms`, symbolic copybook `cpy-bms/COTRTLI.cpy`) defines:

- Top metadata fields like in the update screen (`TRNNAME`, `PGMNAME`, `TITLE01/02`, `CURDATE`, `CURTIME`, `PAGENO`).
- Two filter inputs: `TRTYPE` (2 chars) and `TRDESC` (50 chars).
- A 7-row grid of triplets `(TRTSEL<n>, TRTTYP<n>, TRTYPD<n>)` for n=1..7. The COBOL program does **not** access these by their generated names — instead it overlays the symbolic-map area with a `REDEFINES` of `EACH-ROWI / EACH-ROWO OCCURS 7 TIMES` (defined in-program at lines 434–478 of the COBOL) so the row data is addressable by subscript `(I)`.
- `INFOMSG`, `ERRMSG` and PF-key labels `BUTNF02..BUTNF10`.

**Key copybooks.**

- `COCOM01Y`, `COTTL01Y`, `CSDAT01Y`, `CSMSG01Y`, `CSUSR01Y` — same as `COTRTUPC`.
- `COTRTLI` — BMS symbolic map.
- `CVACT02Y` — `CARD` record layout (included although not used in SQL — left over wiring).
- `CVCRD01Y` — common card-related working storage.
- `CSSTRPFY` — common PF-key store paragraphs.
- `DFHBMSCA`, `DFHAID` — IBM BMS/AID constants.
- DB2-side: `EXEC SQL INCLUDE CSDB2RWY` (Db2 common working storage — `WS-DB2-PROCESSING-FLAG` with 88s `WS-DB2-OK`/`WS-DB2-ERROR`, `WS-DUMMY-DB2-INT`, `WS-DSNTIAC-FORMATTED` 10×72 buffer for `DSNTIAC`-formatted error text), `EXEC SQL INCLUDE SQLCA`, `EXEC SQL INCLUDE DCLTRTYP`, and at the end of the procedure division `EXEC SQL INCLUDE CSDB2RPY` (Db2 common procedures: paragraphs `9998-PRIMING-QUERY` and `9999-FORMAT-DB2-MESSAGE`, the latter calling `LIT-DSNTIAC` (`'DSNTIAC'`) via `CALL ... USING DFHEIBLK, DFHCOMMAREA, SQLCA, WS-DSNTIAC-FORMATTED, WS-DSNTIAC-LRECL`).

**DB2 SQL surface.** Six distinct SQL operations — the most cursor-heavy program in the codebase.

| Statement                  | Cursor / SQL                                                                | Used by                          |
|:---------------------------|:----------------------------------------------------------------------------|:---------------------------------|
| `DECLARE C-TR-TYPE-FORWARD CURSOR` | `SELECT TR_TYPE, TR_DESCRIPTION FROM CARDDEMO.TRANSACTION_TYPE WHERE TR_TYPE >= :WS-START-KEY AND ((:WS-EDIT-TYPE-FLAG = '1' AND TR_TYPE = :WS-TYPE-CD-FILTER) OR (:WS-EDIT-TYPE-FLAG <> '1')) AND ((:WS-EDIT-DESC-FLAG = '1' AND TR_DESCRIPTION LIKE TRIM(:WS-TYPE-DESC-FILTER)) OR (:WS-EDIT-DESC-FLAG <> '1')) ORDER BY TR_TYPE` | `8000-READ-FORWARD` |
| `DECLARE C-TR-TYPE-BACKWARD CURSOR`| Same shape but `WHERE TR_TYPE < :WS-START-KEY` and `ORDER BY TR_TYPE DESC` | `8100-READ-BACKWARDS`           |
| `OPEN C-TR-TYPE-FORWARD`   | (no host-var change)                                                        | `9400-OPEN-FORWARD-CURSOR`       |
| `FETCH C-TR-TYPE-FORWARD INTO :DCL-TR-TYPE, :DCL-TR-DESCRIPTION` |                                       | `8000-READ-FORWARD` (twice — peek-ahead) |
| `CLOSE C-TR-TYPE-FORWARD`  |                                                                             | `9450-CLOSE-FORWARD-CURSOR`      |
| `OPEN C-TR-TYPE-BACKWARD`  |                                                                             | `9500-OPEN-BACKWARD-CURSOR`      |
| `FETCH C-TR-TYPE-BACKWARD INTO :DCL-TR-TYPE, :DCL-TR-DESCRIPTION` |                                       | `8100-READ-BACKWARDS`            |
| `CLOSE C-TR-TYPE-BACKWARD` |                                                                             | `9550-CLOSE-BACK-CURSOR`         |
| `SELECT COUNT(1) INTO :WS-RECORDS-COUNT FROM CARDDEMO.TRANSACTION_TYPE WHERE ...` | (filter precount) | `9100-CHECK-FILTERS` |
| `UPDATE CARDDEMO.TRANSACTION_TYPE SET TR_DESCRIPTION = :DCL-TR-DESCRIPTION WHERE TR_TYPE = :DCL-TR-TYPE` | inline row edit | `9200-UPDATE-RECORD` |
| `DELETE FROM CARDDEMO.TRANSACTION_TYPE WHERE TR_TYPE = :DCL-TR-TYPE` | inline row delete         | `9300-DELETE-RECORD`             |
| `SELECT 1 INTO :WS-DUMMY-DB2-INT FROM SYSIBM.SYSDUMMY1 FETCH FIRST 1 ROW ONLY` | DB2 connectivity ping | `9998-PRIMING-QUERY` (from copybook `CSDB2RPY`) |

Cursor names: `C-TR-TYPE-FORWARD`, `C-TR-TYPE-BACKWARD`. The use of `(:hostvar = '1' AND col = :other) OR (:hostvar <> '1')` is the canonical "optional predicate" pattern in static SQL — you cannot dynamically build a `WHERE` clause without dynamic SQL, so the program switches predicates on by passing `'1'` in `:WS-EDIT-TYPE-FLAG` / `:WS-EDIT-DESC-FLAG`.

The forward read in `8000-READ-FORWARD` issues a *peek-ahead* `FETCH` after the 7th row to set `CA-NEXT-PAGE-EXISTS`/`CA-NEXT-PAGE-NOT-EXISTS` so the screen can decide whether to enable PF08.

`SQLCODE`s explicitly handled: `0` (ok), `+100` (no row / end of cursor), `-532` (FK delete restrict), `-911` (deadlock/timeout). All other negative codes go through `9999-FORMAT-DB2-MESSAGE`, which calls IBM utility `DSNTIAC` to format the SQLCA into 10 lines of 72 chars and returns a long-text message.

**CICS commands used.**

| Command                                    | Where                                  |
|:-------------------------------------------|:---------------------------------------|
| `EXEC CICS RECEIVE MAP(...) INTO(CTRTLIAI) RESP` | `1100-RECEIVE-SCREEN`             |
| `EXEC CICS SEND MAP(...) FROM(CTRTLIAO) CURSOR ERASE FREEKB RESP` | `2600-SEND-SCREEN`  |
| `EXEC CICS SEND TEXT FROM(WS-RETURN-MSG) ERASE FREEKB` | `SEND-PLAIN-TEXT` (debug aid)  |
| `EXEC CICS SEND TEXT FROM(WS-LONG-MSG) ERASE FREEKB` | `SEND-LONG-TEXT` (used when `WS-DB2-ERROR` after `9998-PRIMING-QUERY`) |
| `EXEC CICS SYNCPOINT`                      | After successful `UPDATE`/`DELETE` and before each `XCTL` |
| `EXEC CICS XCTL PROGRAM(...) COMMAREA(CARDDEMO-COMMAREA)` | PF03 exit and PF02 (transfer to `COTRTUPC`) |
| `EXEC CICS RETURN TRANSID('CTLI') COMMAREA(WS-COMMAREA) LENGTH(...)` | `COMMON-RETURN` |

**Major paragraphs / control flow.**

- `0000-MAIN` — restore commarea, `YYYY-STORE-PFKEY` (from `CSSTRPFY`) to normalise PF-key handling, gate the AID against the allowed set (`ENTER`, `PFK02`, `PFK03`, `PFK07`, `PFK08`, `PFK10` only when delete/update was previously confirmed). Critically, runs `9998-PRIMING-QUERY` to verify DB2 connectivity *before* doing any real work, and bails with `SEND-LONG-TEXT` if `WS-DB2-ERROR`. Then a long `EVALUATE TRUE` over PF-key + state to dispatch (page-up, page-down, delete-confirm-then-do, update-confirm-then-do, default = forward read from current key).
- `1000-RECEIVE-MAP` → `1100-RECEIVE-SCREEN` (does the `EXEC CICS RECEIVE MAP` and also copies the 7 row inputs into `WS-EACH-ROW-IN(I)`, blanking `'*'`/spaces) → `1200-EDIT-INPUTS` → `1210-EDIT-ARRAY` (counts `D`/`U` flags via `INSPECT WS-EDIT-SELECT-FLAGS TALLYING ... FOR ALL LIT-DELETE-FLAG / LIT-UPDATE-FLAG`, validates that exactly one row is selected via the 88-level `WS-ONLY-1-ACTION`), `1211-EDIT-ARRAY-DESC` (only when an `U` row is selected — runs the alphanumeric edit on its description), `1220-EDIT-TYPECD` (numeric 2-digit), `1230-EDIT-DESC` (wraps the description filter with `'%'...'%'` so it can flow into the SQL `LIKE` host variable), `1290-CROSS-EDITS` (calls `9100-CHECK-FILTERS` to precount and disable the result grid if the filters return zero rows).
- `8000-READ-FORWARD` — `9400-OPEN-FORWARD-CURSOR`, loop `FETCH` into `DCLTRANSACTION-TYPE` host variables until `WS-MAX-SCREEN-LINES` (= 7) rows are accumulated or `SQLCODE = +100`, store each row in `WS-CA-SCREEN-ROWS-OUT(I)`. After hitting the 7th row, issues one extra `FETCH` to set `CA-NEXT-PAGE-EXISTS` / `CA-NEXT-PAGE-NOT-EXISTS`, then `9450-CLOSE-FORWARD-CURSOR`. The forward cursor is reopened on every keystroke that lists records — DB2 cursors cannot live across pseudo-conversational turns.
- `8100-READ-BACKWARDS` — same shape but using `C-TR-TYPE-BACKWARD` (descending order with `WHERE TR_TYPE < :WS-START-KEY`), filling rows from index 7 down to 1 so the visual order remains ascending.
- `9200-UPDATE-RECORD` / `9300-DELETE-RECORD` — single-row mutations, both gated on `WS-ONLY-1-VALID-ACTION` and `FLG-BAD-ACTIONS-SELECTED-NO`. After success, issue `EXEC CICS SYNCPOINT`. After delete, the program initialises the commarea and resets to first-page state on the next read.
- `2000-SEND-MAP` — same five-step orchestrator pattern as `COTRTUPC` (`2100-SCREEN-INIT` / `2200-SETUP-ARRAY-ATTRIBS` / `2300-SCREEN-ARRAY-INIT` / `2400-SETUP-SCREEN-ATTRS` / `2500-SETUP-MESSAGE` / `2600-SEND-SCREEN`). The array-attrib step recolours one selected row in `DFHNEUTR` and unprotects its description if `U` was selected and only one valid action exists.
- `COMMON-RETURN` — pack `CARDDEMO-COMMAREA` and `WS-THIS-PROGCOMMAREA` into `WS-COMMAREA` and `EXEC CICS RETURN TRANSID('CTLI') COMMAREA(...)`.

**Notable patterns.**

- `EXEC SQL INCLUDE SQLCA`. No `WHENEVER`; explicit `EVALUATE TRUE` after every SQL operation. The `WS-DB2-PROCESSING-FLAG` 88-level (`WS-DB2-ERROR`) is the program-wide circuit breaker that short-circuits the rest of `0000-MAIN` after a fatal connectivity failure.
- The cursor declarations live in the **WORKING-STORAGE SECTION** (lines 338–368 of the COBOL), which is allowed for static cursors and means they are a compile-time fixture, not allocated at runtime.
- The two-step "forward read 7 + 1" pattern in `8000-READ-FORWARD` is how you implement "is there a next page?" without dynamic SQL or a separate `COUNT`.
- DB2 page state lives in the *commarea* (`WS-CA-SCREEN-NUM`, `WS-CA-FIRST-TR-CODE`, `WS-CA-LAST-TR-CODE`, `WS-CA-NEXT-PAGE-IND`), not in a held cursor — pseudo-conversational programs cannot keep DB2 cursors open across user think-time, so the program rebuilds the result set on every keystroke.
- `9999-FORMAT-DB2-MESSAGE` (in copybook `CSDB2RPY`) does `CALL 'DSNTIAC' USING DFHEIBLK, DFHCOMMAREA, SQLCA, WS-DSNTIAC-FORMATTED, WS-DSNTIAC-LRECL` to ask DB2 to render the SQLCA into human-readable text. This is the IBM-blessed way to surface DB2 errors in a CICS screen.

### 4.3 `COBTUPDT` — batch loader (insert/update/delete from flat file)

File: `app/app-transaction-type-db2/cbl/COBTUPDT.cbl` (237 lines — small).

**Purpose.** Batch driver that reads a sequential file (`INPFILE` DD), and for each record executes one of `INSERT`/`UPDATE`/`DELETE` against `CARDDEMO.TRANSACTION_TYPE` based on a 1-character action flag.

**Batch.** Driven by `jcl/MNTTRDB2.jcl`. Run under `IKJEFT01` with `DSN SYSTEM(DAZ1) RUN PROGRAM(COBTUPDT) PLAN(CARDDEMO)` — the batch attachment, so the program connects to DB2 directly rather than via the CICS attachment. Not a CICS program (no `EXEC CICS`).

**BMS maps.** None — pure batch.

**Key copybooks.**

- DB2 only: `EXEC SQL INCLUDE SQLCA`, `EXEC SQL INCLUDE DCLTRTYP`. No application copybooks.

**Input file.**

```
SELECT TR-RECORD ASSIGN TO INPFILE
       ORGANIZATION IS SEQUENTIAL
       ACCESS MODE  IS SEQUENTIAL
       FILE STATUS  IS WS-INF-STATUS.
```

Record layout (53 bytes, fixed):

| Cols | Field              | Meaning                                        |
|:-----|:-------------------|:-----------------------------------------------|
| 1    | `INPUT-REC-TYPE`   | `A` add, `U` update, `D` delete, `*` comment   |
| 2-3  | `INPUT-REC-NUMBER` | 2-char transaction type code                   |
| 4-53 | `INPUT-REC-DESC`   | 50-char description (only used by `A`/`U`)     |

**DB2 SQL surface.** Three statements, all on `CARDDEMO.TRANSACTION_TYPE`. Note `COBTUPDT` binds host variables directly to the *file record* fields, not to the DCLGEN host variables — it does not use `DCL-TR-TYPE` / `DCL-TR-DESCRIPTION` even though it `INCLUDE`s `DCLTRTYP`.

| Paragraph          | SQL                                                                     |
|:-------------------|:------------------------------------------------------------------------|
| `10031-INSERT-DB`  | `INSERT INTO CARDDEMO.TRANSACTION_TYPE (TR_TYPE, TR_DESCRIPTION) VALUES (:INPUT-REC-NUMBER, :INPUT-REC-DESC)` |
| `10032-UPDATE-DB`  | `UPDATE CARDDEMO.TRANSACTION_TYPE SET TR_DESCRIPTION = :INPUT-REC-DESC WHERE TR_TYPE = :INPUT-REC-NUMBER` |
| `10033-DELETE-DB`  | `DELETE FROM CARDDEMO.TRANSACTION_TYPE WHERE TR_TYPE = :INPUT-REC-NUMBER` |

`SQLCODE` handling:

- `= 0` → display "RECORD INSERTED/UPDATED/DELETED SUCCESSFULLY".
- `= +100` (update/delete only) → string "No records found." into `WS-RETURN-MSG` and `PERFORM 9999-ABEND`.
- `< 0` → format `'Error accessing: TRANSACTION_TYPE table. SQLCODE:' WS-VAR-SQLCODE` and `PERFORM 9999-ABEND`.

There is no explicit `COMMIT` in the program — relying on the implicit commit at successful job end (the DSN run command).

**CICS commands.** None.

**Major paragraphs / control flow.**

- `0001-OPEN-FILES` — `OPEN INPUT TR-RECORD`, check `WS-INF-STATUS = '00'`.
- `1001-READ-NEXT-RECORDS` — drives the loop: read one, then `PERFORM UNTIL LASTREC = 'Y'` repeat (treat → read).
- `1002-READ-RECORDS` — `READ TR-RECORD NEXT RECORD INTO WS-INPUT-REC AT END MOVE 'Y' TO LASTREC`.
- `1003-TREAT-RECORD` — `EVALUATE INPUT-REC-TYPE` dispatches to `10031`/`10032`/`10033`/skip (`*`)/abend.
- `2001-CLOSE-STOP` — `CLOSE TR-RECORD`.
- `9999-ABEND` — `DISPLAY WS-RETURN-MSG`, `MOVE 4 TO RETURN-CODE`. (Note: it does *not* `STOP RUN` — control returns to the caller. The actual `STOP RUN` is on line 99 of the program, after `2001-CLOSE-STOP`.)

**Notable patterns.**

- `EXEC SQL INCLUDE SQLCA` only (no application Db2-common copybooks). No `WHENEVER`. `MOVE SQLCODE TO WS-VAR-SQLCODE` (`PIC ----9` for display formatting).
- Host variables are *directly the file record fields* — `:INPUT-REC-NUMBER`, `:INPUT-REC-DESC`. This works because their PIC clauses (`X(2)`, `X(50)`) match the column types. No conversion between input and DCLGEN buffers.
- `RECORDING MODE F` plus the JCL DD statement implies fixed-length 53-byte records (the `WS-INPUT-VARS` group totals 53 bytes), but the JCL leaves DCB to the catalog — the DD just says `DSN=INPFILE,DISP=SHR`.

---

## 5. Batch jobs (`jcl/`)

### 5.1 `jcl/CREADB21.jcl` — one-time DB2 setup

Bootstraps the entire DB2 environment for this module. Job is submitted with `TYPRUN=SCAN` by default — operator must remove that to actually run it. Steps:

- `FREEPLN` — runs `IKJEFT01` against `ctl/DB2FREE.ctl` to `FREE PLAN(CARDDEMO)`, `FREE PLAN(COTRTLIC)`, `FREE PACKAGE(COTRTLIC.*)`. Comment warns this RC=8s if the plans don't yet exist; skip on first install.
- `CRCRDDB` — runs `DSNTIAD` (TSO terminal interface, batch DDL utility) via `ctl/DB2TIAD1.ctl` driver and `ctl/DB2CREAT.ctl` SYSIN. This creates `CARDDEMO` database, `CARDSPC1` and `CARDSTTC` tablespaces, both tables, both unique indices, the `FOREIGN KEY ... ON DELETE RESTRICT` constraint, and the `GRANT ... TO PUBLIC` statements.
- `LDTTYPE` — `IEFBR14` placeholder (used for conditional flow control).
- `RUNTEP2` — runs `DSNTEP4` via `ctl/DB2TEP41.ctl` and `ctl/DB2LTTYP.ctl` to seed `TRANSACTION_TYPE` with the 7 base codes (`01 PURCHASE`, `02 PAYMENT`, ..., `07 ADJUSTMENT`). Note the typo `'06','REVERAL'` in `DB2LTTYP.ctl` — preserved as-is.
- `LDTCCAT` — runs `DSNTEP4` against `ctl/DB2LTCAT.ctl` to seed 18 rows into `TRANSACTION_TYPE_CATEGORY`. Conditioned on `(0,NE)` so all preceding steps must succeed.

### 5.2 `jcl/TRANEXTR.jcl` — daily DB2 → flat-file extract

The DB2-to-VSAM-compatible-PS bridge that keeps the *core* application's view of transaction types in sync with DB2 admin updates. Steps:

- `STEP10` / `STEP20` — `IEBGENER` backups of the previous `&HLQ..TRANTYPE.PS` and `&HLQ..TRANCATG.PS` to GDG generations `(+1)` (DCB `LRECL=60,RECFM=FB,BLKSIZE=600`).
- `STEP30` — `IEFBR14` deletes the previous PS files so the next steps can recreate them.
- `STEP40` — `DSNTIAUL` extract from `TRANSACTION_TYPE`. The inline `SYSIN` SQL is interesting:

  ```sql
  SELECT CAST(CONCAT(CONCAT(
      TR_TYPE,
      CAST(TR_DESCRIPTION AS CHAR(50))
    ), REPEAT('0',8)) AS CHAR(60))
  FROM CARDDEMO.TRANSACTION_TYPE
  ORDER BY TR_TYPE;
  ```

  Result is a single 60-byte text column = `TR_TYPE(2) || TR_DESCRIPTION-as-CHAR(50) || '00000000'(8)`. The 8-byte trailing zeros pad to the 60-byte record layout the core's `TRANTYPE` consumer expects (and matches the `FILLER PIC X(08)` in `TTYP-UPDATE-RECORD` of `COTRTUPC`).

- `STEP50` — `DSNTIAUL` extract from `TRANSACTION_TYPE_CATEGORY`, padding the 56-byte `(2 + 4 + 50)` row with `REPEAT('0',4)` to match the 60-byte layout.

`DSNTIAUL` writes each row in the inline SQL to `SYSREC00` as text (since `PARMS('SQL')` is set). The output PS files (`&HLQ..TRANTYPE.PS`, `&HLQ..TRANCATG.PS`) are what the core CardDemo reference-data loader consumes.

### 5.3 `jcl/MNTTRDB2.jcl` — batch maintenance

Single-step job that runs `COBTUPDT` under `IKJEFT01` with `PLAN(CARDDEMO)`. Reads `INPFILE` DD and applies adds/updates/deletes to `CARDDEMO.TRANSACTION_TYPE`. The header comment documents the input format (column 1 = `A`/`D`/`U`/`*`, columns 2-3 = type code, columns 4-53 = description). No `DD` for output beyond `SYSTSPRT`.

---

## 6. Cross-module wiring

**Calls into core CardDemo programs:** None directly via `CALL`. The wiring is via:

- `EXEC CICS XCTL` to `COADM01C` (admin menu) on PF03 exit (both `COTRTUPC` and `COTRTLIC`).
- `EXEC CICS XCTL` between `COTRTUPC` ↔ `COTRTLIC` (PF02 from list to add screen, and from `COTRTUPC` PF03 back to whoever called it — could be either).
- A `CALL 'DSNTIAC'` (DB2-supplied module, not part of CardDemo) inside `CSDB2RPY` for SQLCA formatting in `COTRTLIC`.

**VSAM writes from this module:** None. Neither `COTRTUPC`, `COTRTLIC`, nor `COBTUPDT` opens or writes to the core `TRANTYPE` VSAM cluster directly. All maintenance lands in DB2.

**How DB2 stays in sync with the core's VSAM `TRANTYPE`:** It does not, in real-time. The batch job `TRANEXTR` (run daily per the JCL header comment) is the one-way pipe:

```
DB2 CARDDEMO.TRANSACTION_TYPE
        │  (DSNTIAUL extract, 60-byte CHAR rows)
        ▼
&HLQ..TRANTYPE.PS  (sequential, FB 60)
        │  (consumed by core reference-data loader)
        ▼
TRANTYPE VSAM KSDS
```

So:

- The DB2 tables are the *system of record* for administration (CRUD operations from `CTTU`/`CTLI` and the batch loader).
- The core CardDemo runtime never reads DB2 — it reads the VSAM file populated from the daily `TRANEXTR` extract.
- Therefore: DB2 changes only become visible to core transaction processing after the next `TRANEXTR` run. The README calls this a "Dual Storage Strategy" — DB2 for admin convenience and reporting flexibility, VSAM for transaction-path performance.

This split is also why `COBTUPDT` exists separately from any VSAM updater: the same logical update has to be applied to DB2 first (here), then propagated to VSAM via `TRANEXTR` and the core's load step.

**Plan / package binding:** Both online programs run under DB2 plan `CARDDEMO` (per the `DB2ENTRY` definition in `csd/CRDDEMOD.csd` and the `RUN PROGRAM(...) PLAN(CARDDEMO)` in `MNTTRDB2.jcl`). The `DB2FREE.ctl` script also frees a `PLAN(COTRTLIC)` and `PACKAGE(COTRTLIC.*)`, suggesting `COTRTLIC` was at one point bound as a separate package — useful to know if you are tracing why a `BIND PACKAGE` step is missing from `CREADB21`.

---

## 7. Java porting notes

A few non-obvious points for anyone planning a Java migration of this module:

- The `VARCHAR(50)` columns surface as a 2-byte length + 50-byte text pair through DCLGEN. In Java/JDBC this collapses to a single `String` — there is no analogue of the `(1: DCL-TR-DESCRIPTION-LEN)` substring trick. JPA `@Column(length = 50)` is sufficient.
- `CHAR(2)` for `TR_TYPE` is space-padded in DB2. If you migrate to MariaDB/MySQL `VARCHAR(2)` or PostgreSQL `varchar(2)` you lose the implicit padding; add an explicit `String.format("%-2s", code)` (or change everything to `varchar` and trim consistently) to keep equality with old data.
- The "optional predicate" pattern `(:flag = '1' AND col = :val) OR :flag <> '1'` exists *only* because static SQL forbids dynamic `WHERE` construction. In Java, use a `CriteriaQuery`, JOOQ DSL, MyBatis `<if>`, or a parameterised string with conditional clause assembly. Do not literally translate the COBOL form — it defeats the optimiser.
- Cursor pseudo-conversational pattern (close on every screen, re-open with a new `WHERE TR_TYPE >= :startKey`) maps to keyset (seek) pagination in JDBC. Do **not** translate to `OFFSET` — it has different semantics under concurrent inserts/deletes and the COBOL clearly relies on key-relative paging.
- `EXEC CICS SYNCPOINT` after every successful mutation = `EntityManager.flush()` + `transaction.commit()` in JPA, or just relying on Spring's `@Transactional` auto-commit at method exit.
- `SQLCODE = -911` (deadlock/timeout) maps to `SQLTransientException` / `CannotAcquireLockException` (Spring). `SQLCODE = -532` (FK delete restrict) maps to `DataIntegrityViolationException`. `SQLCODE = +100` (no row) is `SELECT INTO` returning empty — JPA throws `NoResultException`, JDBC returns `false` from `ResultSet.next()`.
- The state-machine flag `TTUP-CHANGE-ACTION` (a single byte with ~12 88-level values) is a textbook UI workflow enum. A Java port should make this an explicit `enum TtUpAction { DETAILS_NOT_FETCHED, SHOW_DETAILS, CONFIRM_DELETE, ... }` rather than a `String` or `int`.
- `DSNTIAC` exists to render an `SQLCA` to readable text. In Java/JDBC, `SQLException.getMessage()` already gives you the equivalent — no separate utility needed.
- The DB2-to-VSAM bridge via `TRANEXTR` is a *planned eventual-consistency* design. A modern port can either keep the dual-write story (cleaner if you must preserve the core's runtime behaviour byte-for-byte) or collapse to a single source of truth in DB2/RDBMS once the core's reference-data loader is also rewritten.
