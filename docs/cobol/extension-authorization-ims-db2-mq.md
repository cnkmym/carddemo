# Extension Module: `app-authorization-ims-db2-mq`

This is the deep dive for the most complex CardDemo extension, which adds a
real-time credit card authorization subsystem on top of the VSAM-only core.
It combines three storage technologies that a Java developer rarely meets all
at once:

- **IMS DB** — a hierarchical database (think: a single-rooted JSON document
  tree per account, persisted on VSAM under the covers) holding pending
  authorizations.
- **DB2** — a relational table that captures authorizations a human reviewer
  has flagged as fraudulent (analytics/audit copy).
- **MQ** — IBM MQ queues that carry inbound authorization requests from a
  point-of-sale (POS) emulator and outbound responses back to it.
- **CICS + BMS + VSAM** — for online review screens and the existing
  account/customer/card cross-reference data, which is shared with the core
  module.

For project-wide architecture, see
[`../architecture.md`](../architecture.md). For directory layout, see
[`../directory-guide.md`](../directory-guide.md). This document is
program-by-program detail and does not duplicate them.

---

## 1. Module overview

A *credit card authorization* in this codebase is a single message — sent
when a cardholder swipes their card at a merchant — that asks "should this
purchase be approved?". The CardDemo authorization module simulates the
issuer side of that flow:

1. A POS emulator (out of repo, any MQ-compatible client will do) puts a
   comma-separated request message on the queue
   `AWS.M2.CARDDEMO.PAUTH.REQUEST`.
2. CICS sees the queue activity and, via trigger monitoring, starts
   transaction `CP00`. That transaction runs program
   `app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl`.
3. `COPAUA0C` reads the request, looks up the card → account → customer
   chain in the **core** VSAM datasets (`CCXREF`, `ACCTDAT`, `CUSTDAT`),
   reads the existing IMS authorization summary for the account, applies
   business rules (credit limit check, etc.), and emits an approve/decline
   response on the reply queue carried by the request's `MQMD-REPLYTOQ`.
4. The same program then writes the authorization into IMS — updating the
   per-account `PAUTSUM0` summary segment and inserting a new `PAUTDTL1`
   detail segment as its child.
5. A back-office user can later open the authorization summary screen
   (transaction `CPVS`, program `COPAUS0C`) and the per-authorization detail
   screen (`CPVD`, `COPAUS1C`) to inspect or take action on those
   authorizations.
6. From the detail screen, pressing PF5 LINKs to `COPAUS2C`, which copies
   the authorization into the DB2 fraud table `CARDDEMO.AUTHFRDS` (insert
   or update) and updates the IMS detail segment's fraud flag.
7. Nightly, the batch job `CBPAUP0J` runs `CBPAUP0C` to delete IMS
   authorization details older than N days and update the parent summary
   counters.

Three new CICS transaction codes are introduced: `CP00` (auth request
processor), `CPVS` (auth summary view), `CPVD` (auth detail view).

---

## 2. IMS database schema

There are two physical IMS databases in this module — a HIDAM primary and
its secondary index — plus two GSAM databases used only for batch
load/unload.

### `DBPAUTP0` — HIDAM primary (the actual authorization store)

Source: `app/app-authorization-ims-db2-mq/ims/DBPAUTP0.dbd`.

```
DBD     NAME=DBPAUTP0,ACCESS=(HIDAM,VSAM),...
DSG001  DATASET DD1=DDPAUTP0,SIZE=(4096),SCAN=3
SEGM    NAME=PAUTSUM0,PARENT=0,BYTES=100,RULES=(,HERE),POINTER=(TWINBWD)
  FIELD NAME=(ACCNTID,SEQ,U),START=1,BYTES=6,TYPE=P
  LCHILD NAME=(PAUTINDX,DBPAUTX0),POINTER=INDX
SEGM    NAME=PAUTDTL1,PARENT=((PAUTSUM0,)),BYTES=200
  FIELD NAME=(PAUT9CTS,SEQ,U),START=1,BYTES=8,TYPE=C
```

Hierarchy:

```
DBPAUTP0  (HIDAM, VSAM-backed)
└── PAUTSUM0   (root, 100 bytes)        — Pending Authorization Summary
    │   key  = ACCNTID, packed decimal, 6 bytes  (PIC S9(11) COMP-3)
    │   layout: cpy/CIPAUSMY.cpy
    │   pointer = TWINBWD (twin backward; for chaining roots)
    │
    └── PAUTDTL1   (child, 200 bytes)   — Pending Authorization Details
        key  = PAUT9CTS, char, 8 bytes  (= PA-AUTHORIZATION-KEY)
        layout: cpy/CIPAUDTY.cpy
```

Key things to notice for a Java developer:

- A Java analogue is roughly:
  `Map<AccountId, AuthorizationSummary> store; class AuthorizationSummary { List<AuthorizationDetail> details; }`,
  where the map and list are *physically* clustered on disk.
- The root key `ACCNTID` is the account ID. PIC type `P` means **packed
  decimal** — 6 bytes hold up to 11 significant digits plus a sign nibble.
  In COBOL the field is `PA-ACCT-ID PIC S9(11) COMP-3`.
- The detail key `PAUT9CTS` is character (`TYPE=C`). It is built in
  `cpy/CIPAUDTY.cpy` as the concatenation of `PA-AUTH-DATE-9C` (5-byte
  packed decimal) and `PA-AUTH-TIME-9C` (5-byte packed decimal), totalling
  8 bytes when treated as character. Both halves are stored as `99999 -
  date` and `999999999 - time` so that newest-first ordering is achieved
  by IMS's natural ascending sort — see `8500-INSERT-AUTH` in `COPAUA0C`.
- `RULES=(,HERE)` on the root means new sibling roots are inserted *here*
  (current position) rather than at the end. Combined with `POINTER=
  (TWINBWD)` this allows efficient backward navigation among root twins.
- `LCHILD ... POINTER=INDX` connects this DBD to the secondary index DBD.

### `DBPAUTX0` — HIDAM secondary index

Source: `app/app-authorization-ims-db2-mq/ims/DBPAUTX0.dbd`.

```
DBD     NAME=DBPAUTX0,ACCESS=(INDEX,VSAM,PROT),...
DSG001  DATASET DD1=DDPAUTX0,SIZE=(4096)
SEGM    NAME=PAUTINDX,PARENT=0,BYTES=6,FREQ=100000
  FIELD NAME=(INDXSEQ,SEQ,U),START=1,BYTES=6,TYPE=P
  LCHILD NAME=(PAUTSUM0,DBPAUTP0),INDEX=ACCNTID
```

This is the index *every* HIDAM database needs — it maps the root key
(`ACCNTID` of `PAUTSUM0`) to the physical RBA of the root. The DD name
`DDPAUTX0` is the sister of `DDPAUTP0`. `FREQ=100000` is a population
estimate the IMS DBA uses for space planning.

### `PASFLDBD` and `PADFLDBD` — GSAM (batch only)

Two **GSAM** (Generalized Sequential Access Method) databases used only by
the batch load/unload programs. GSAM lets a batch program treat a flat
sequential file as if it were an IMS database, picking up commit/restart
behaviour from IMS:

- `PASFLDBD` — root-segment flat file, `RECORD=(100)`, DDs `PASFILIP`
  (input) / `PASFILOP` (output).
- `PADFLDBD` — child-segment flat file, `RECORD=(200)`, DDs `PADFILIP` /
  `PADFILOP`.

The 100/200 byte sizes match the `PAUTSUM0` / `PAUTDTL1` segment sizes.

---

## 3. PSB views

A PSB (Program Specification Block) is IMS's permission descriptor — it
tells IMS which database segments a program may touch and how. Java
analogue: a Spring `@Repository` interface restricted by JPA `@RolesAllowed`
plus a hard-coded transaction propagation level.

### `PSBPAUTB` — read/write view (used by online and the BMP purge job)

`app/app-authorization-ims-db2-mq/ims/PSBPAUTB.psb`:

```
PAUTBPCB PCB   TYPE=DB,DBDNAME=DBPAUTP0,PROCOPT=AP,KEYLEN=14
         SENSEG NAME=PAUTSUM0,PARENT=0
         SENSEG NAME=PAUTDTL1,PARENT=PAUTSUM0
         PSBGEN LANG=COBOL,PSBNAME=PSBPAUTB,CMPAT=YES
```

- `PROCOPT=AP` — `A`ll operations (Get, Insert, Replace, Delete) plus
  `P`ositioning by command code. Read AND write.
- Both segments are sensitive (visible) to the program.
- `KEYLEN=14` = 6-byte root key + 8-byte child key concatenated.
- Used by: `COPAUA0C` (online auth processor), `COPAUS0C` (online summary),
  `COPAUS1C` (online detail), `CBPAUP0C` (BMP batch purge).

### `PSBPAUTL` — load view (sequential load only)

```
PAUTLPCB PCB   TYPE=DB,DBDNAME=DBPAUTP0,PROCOPT=L,KEYLEN=14
         SENSEG NAME=PAUTSUM0,PARENT=0
         SENSEG NAME=PAUTDTL1,PARENT=PAUTSUM0
         PSBGEN LANG=ASSEM,PSBNAME=PSBPAUTL
```

- `PROCOPT=L` — load only. Used during initial database population.
- `LANG=ASSEM` is a quirk; the load view is generated as if the calling
  program is assembler. In practice it's used only by IMS's own load utility
  or as the empty-DB initializer.

### `PAUTBUNL` — unload-only view

`app/app-authorization-ims-db2-mq/ims/PAUTBUNL.PSB`:

```
PAUTBPCB PCB   TYPE=DB,DBDNAME=DBPAUTP0,PROCOPT=GOTP,KEYLEN=14
         SENSEG NAME=PAUTSUM0,PARENT=0
         SENSEG NAME=PAUTDTL1,PARENT=PAUTSUM0
         PSBGEN LANG=COBOL,PSBNAME=PAUTBUNL,CMPAT=NO
```

- `PROCOPT=GOTP` — Get + read-only with command codes; `T` = procopt
  override permitted at PCB level. Used by `PAUDBUNL` (unload-to-flat-file
  utility).

### `DLIGSAMP` — unload with GSAM output

`app/app-authorization-ims-db2-mq/ims/DLIGSAMP.PSB`:

```
PAUTBPCB PCB   TYPE=DB,DBDNAME=DBPAUTP0,PROCOPT=GOTP,KEYLEN=14
         SENSEG NAME=PAUTSUM0,PARENT=0
         SENSEG NAME=PAUTDTL1,PARENT=PAUTSUM0
         PCB   TYPE=GSAM,DBDNAME=PASFLDBD,PROCOPT=LS
         PCB   TYPE=GSAM,DBDNAME=PADFLDBD,PROCOPT=LS
         PSBGEN LANG=COBOL,PSBNAME=DLIGSAMP,CMPAT=NO
```

- Three PCBs in one PSB: PCB#1 reads `DBPAUTP0`, PCB#2 writes the GSAM
  root file, PCB#3 writes the GSAM child file. `PROCOPT=LS` = Load
  Sequential. Used by `DBUNLDGS`.

### Which program uses which PSB

| PSB         | Programs                                       | Mode       |
|:-----------:|:-----------------------------------------------|:-----------|
| `PSBPAUTB`  | `COPAUA0C`, `COPAUS0C`, `COPAUS1C`, `CBPAUP0C`, `PAUDBLOD` | Read/write |
| `PSBPAUTL`  | (initial load, no in-repo program)             | Load       |
| `PAUTBUNL`  | `PAUDBUNL`                                     | Read-only unload |
| `DLIGSAMP`  | `DBUNLDGS`                                     | Read DB + write GSAM |

Note: the `PSB-NAME` field in the online and BMP programs is hard-coded to
`'PSBPAUTB'`, but their `PCB-OFFSET PAUT-PCB-NUM` differs:

- Online (`COPAUA0C`, `COPAUS0C`, `COPAUS1C`) uses `+1` because under CICS
  + DLI the *first* PCB is the database PCB.
- Batch BMP (`CBPAUP0C`) uses `+2` because in a true batch BMP the first
  PCB position is the I/O-PCB; the database PCB sits at `+2`.

---

## 4. DB2 fraud table

### `CARDDEMO.AUTHFRDS`

Source: `app/app-authorization-ims-db2-mq/ddl/AUTHFRDS.ddl`. Schema:

```
CREATE TABLE CARDDEMO.AUTHFRDS
( CARD_NUM              CHAR(16)    NOT NULL,
  AUTH_TS               TIMESTAMP   NOT NULL,
  AUTH_TYPE             CHAR(4),
  CARD_EXPIRY_DATE      CHAR(4),
  MESSAGE_TYPE          CHAR(6),
  MESSAGE_SOURCE        CHAR(6),
  AUTH_ID_CODE          CHAR(6),
  AUTH_RESP_CODE        CHAR(2),
  AUTH_RESP_REASON      CHAR(4),
  PROCESSING_CODE       CHAR(6),
  TRANSACTION_AMT       DECIMAL(12,2),
  APPROVED_AMT          DECIMAL(12,2),
  MERCHANT_CATAGORY_CODE CHAR(4),
  ACQR_COUNTRY_CODE     CHAR(3),
  POS_ENTRY_MODE        SMALLINT,
  MERCHANT_ID           CHAR(15),
  MERCHANT_NAME         VARCHAR(22),
  MERCHANT_CITY         CHAR(13),
  MERCHANT_STATE        CHAR(2),
  MERCHANT_ZIP          CHAR(9),
  TRANSACTION_ID        CHAR(15),
  MATCH_STATUS          CHAR(1),
  AUTH_FRAUD            CHAR(1),
  FRAUD_RPT_DATE        DATE,
  ACCT_ID               DECIMAL(11),
  CUST_ID               DECIMAL(9),
  PRIMARY KEY(CARD_NUM, AUTH_TS) );
```

The primary key is composite: `(CARD_NUM, AUTH_TS)`. Note that `MERCHANT_NAME`
is the only `VARCHAR` column (host variable has the IBM 2-byte length prefix
group — see `MERCHANT-NAME-LEN` / `MERCHANT-NAME-TEXT` in the DCL below).

### `CARDDEMO.XAUTHFRD` — index

`ddl/XAUTHFRD.ddl`:

```
CREATE UNIQUE INDEX CARDDEMO.XAUTHFRD
  ON CARDDEMO.AUTHFRDS (CARD_NUM ASC, AUTH_TS DESC)
  COPY YES;
```

Same column set as the PK but with `AUTH_TS DESC` so the most recent fraud
report for a given card sorts first — handy for retrieval.

### `DCLGEN` host-variable layout

Source: `app/app-authorization-ims-db2-mq/dcl/AUTHFRDS.dcl`. Generated by
DB2's `DCLGEN` utility — equivalent to JPA generating an `@Entity` class
from a table:

```
01  DCLAUTHFRDS.
    10 CARD-NUM             PIC X(16).
    10 AUTH-TS              PIC X(26).        — TIMESTAMP rendered as char
    10 AUTH-TYPE            PIC X(4).
    ...
    10 TRANSACTION-AMT      PIC S9(10)V9(2) USAGE COMP-3.    — DECIMAL(12,2)
    10 APPROVED-AMT         PIC S9(10)V9(2) USAGE COMP-3.
    ...
    10 POS-ENTRY-MODE       PIC S9(4) USAGE COMP.            — SMALLINT
    10 MERCHANT-ID          PIC X(15).
    10 MERCHANT-NAME.                                        — VARCHAR(22)
       49 MERCHANT-NAME-LEN  PIC S9(4) USAGE COMP.
       49 MERCHANT-NAME-TEXT PIC X(22).
    ...
    10 ACCT-ID              PIC S9(11)V USAGE COMP-3.
    10 CUST-ID              PIC S9(9)V USAGE COMP-3.
```

Java equivalents in one line each: `CHAR(n)` → `String` (right-padded with
spaces, length n); `DECIMAL(p,s)` packed → `BigDecimal` of scale `s`;
`TIMESTAMP` → `LocalDateTime` or `Instant`; `DATE` → `LocalDate`;
`SMALLINT` → `short`; `VARCHAR(n)` → `String` (no padding, but in COBOL it
becomes a 2-byte length plus a fixed-size buffer).

The DCL declares the table as `CARDDEMO.AUTHFRDS`. The DCLGEN comment
header references `AWSTSSC.AUTHFRDS`, which is a stale schema name; the
in-repo DDL and `COPAUS2C` both use `CARDDEMO`.

---

## 5. MQ resources

### Queues

The README documents:

| Queue                              | Direction | Used by                  |
|:-----------------------------------|:----------|:-------------------------|
| `AWS.M2.CARDDEMO.PAUTH.REQUEST`    | Inbound   | POS emulator → `COPAUA0C` (`MQOPEN` shared input + `MQGET`) |
| `AWS.M2.CARDDEMO.PAUTH.REPLY`      | Outbound  | `COPAUA0C` → POS emulator (`MQPUT1`); the actual reply queue is taken from the request message's `MQMD-REPLYTOQ` |

There are no MQ resources defined in the in-repo CSD
(`csd/CRDDEMO2.csd`) — these are objects of the IBM MQ subsystem rather
than CICS, and would be defined to the MQ queue manager separately. The
queue *names* arrive at runtime via the CICS trigger monitor (`MQTM`
trigger message) — see step 1000 of `COPAUA0C`.

### MQ verbs used

In `COPAUA0C.cbl`:

- `MQOPEN` — open the trigger-supplied request queue (`MQOO-INPUT-SHARED`).
- `MQGET` — pull next message; options
  `MQGMO-NO-SYNCPOINT + MQGMO-WAIT + MQGMO-CONVERT + MQGMO-FAIL-IF-QUIESCING`,
  wait interval 5000 ms.
- `MQPUT1` — put a single reply message (no separate `MQOPEN` for reply
  queue); options `MQPMO-NO-SYNCPOINT + MQPMO-DEFAULT-CONTEXT`.
- `MQCLOSE` — close request queue at termination.

The trigger message structure is `MQTM` (`COPY CMQTML.`), pulled from
EIB via `EXEC CICS RETRIEVE`. Standard MQ vendor copybooks consumed:
`CMQODV` (object descriptor), `CMQMDV` (message descriptor), `CMQV`
(constants), `CMQPMOV`/`CMQGMOV` (put/get options), `CMQTML` (trigger).

---

## 6. CICS resources

Source: `app/app-authorization-ims-db2-mq/csd/CRDDEMO2.csd`. Tabulated:

### Mapsets

| Mapset    | Description                              |
|:----------|:-----------------------------------------|
| `COPAU00` | Authorization summary screen (5-row list) |
| `COPAU01` | Authorization detail screen              |

### Programs

| Program    | Language | Trigger             | Notes                           |
|:-----------|:---------|:--------------------|:--------------------------------|
| `COPAUA0C` | COBOL    | `CP00` (MQ trigger) | MQ + IMS + VSAM auth processor  |
| `COPAUS0C` | COBOL    | `CPVS`              | IMS + BMS summary screen        |
| `COPAUS1C` | COBOL    | `CPVD`              | IMS + BMS detail screen         |
| `COPAUS2C` | COBOL    | `CPVD` (LINK target) | DB2 fraud insert/update         |

### Transactions

| TRAN  | Program    | Notes                                                |
|:------|:-----------|:-----------------------------------------------------|
| `CP00`| `COPAUA0C` | Started via MQ trigger monitor                       |
| `CPVS`| `COPAUS0C` | User-initiated                                       |
| `CPVD`| `COPAUS1C` | User-initiated; also the DB2-entry transaction      |

### DB2 wiring

```
DEFINE DB2ENTRY(AWS01PLN) GROUP(CARDDEMO)
       PLAN(AWS01PLN) DROLLBACK(YES) THREADLIMIT(1) THREADWAIT(YES) ...
DEFINE DB2TRAN(CPVDTRAN) GROUP(CARDDEMO)
       ENTRY(AWS01PLN) TRANSID(CPVD)
```

Notes:

- The CICS-DB2 attachment binds plan `AWS01PLN` to transaction `CPVD`.
  When `COPAUS1C` (running under `CPVD`) does `EXEC CICS LINK
  PROGRAM('COPAUS2C')`, the linked program runs under the same DB2 plan
  and can issue `EXEC SQL`.
- `DROLLBACK(YES)` means deadlock victims are automatically rolled back.
- `THREADLIMIT(1) THREADWAIT(YES)` serialises DB2 thread allocation —
  expected since DB2 is exercised only when a user marks fraud, an
  infrequent action.

---

## 7. COBOL programs

There are eight `.cbl`/`.CBL` files in `cbl/`. Below is one section per
program.

### 7.1 `COPAUA0C` — authorization request processor

File: `app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl` (1027 lines).

**Purpose.** Reads up to 500 authorization requests per task from the MQ
request queue, decides approve/decline using VSAM and IMS data, replies on
the MQ reply queue, persists each decision into IMS.

**Online vs. batch.** Online, CICS transaction `CP00`. Triggered by the MQ
trigger monitor when messages arrive on `AWS.M2.CARDDEMO.PAUTH.REQUEST`.
The program retrieves the trigger data via `EXEC CICS RETRIEVE INTO(MQTM)`,
which gives it the queue name and trigger payload.

**PSB used.** `PSBPAUTB` (PCB#1 = `PAUT-PCB-NUM = +1`).

**Key copybooks.**

- `CCPAURQY` (request layout — 18 fields, comma-separated on the wire).
- `CCPAURLY` (response layout — 6 fields).
- `CCPAUERY` (`ERROR-LOG-RECORD`, written to TD queue `CSSL`).
- `CIPAUSMY`, `CIPAUDTY` (IMS segment layouts).
- `CVACT01Y` (account record), `CVACT03Y` (card cross-ref), `CVCUS01Y`
  (customer) — **inherited from the core module**.
- IBM MQ vendor copybooks: `CMQODV`, `CMQMDV`, `CMQV`, `CMQPMOV`,
  `CMQGMOV`, `CMQTML`.

**IMS DL/I call surface.** All access via `EXEC DLI` (the macro form,
preprocessed into `CBLTDLI` calls):

| Para           | Function | Segment(s)                | SSA / qualifier                                      |
|:---------------|:---------|:--------------------------|:-----------------------------------------------------|
| `1200-SCHEDULE-PSB` | `SCHD` | (PSB-level)               | `PSB((PSB-NAME))`, `NODHABEND`                       |
| `5500-READ-AUTH-SUMMRY` | `GU` | `PAUTSUM0`           | `WHERE (ACCNTID = PA-ACCT-ID)`                       |
| `8400-UPDATE-SUMMARY` | `REPL` | `PAUTSUM0`             | (current position from prior `GU`)                   |
| `8400-UPDATE-SUMMARY` | `ISRT` | `PAUTSUM0`             | (used when `NFOUND-PAUT-SMRY-SEG` — first auth ever) |
| `8500-INSERT-AUTH` | `ISRT` | `PAUTSUM0` + `PAUTDTL1` | path call: `WHERE (ACCNTID=PA-ACCT-ID)` for parent then `FROM PENDING-AUTH-DETAILS` for child |
| `9000-TERMINATE` | `TERM`   | (PSB-level)              | terminates the PSB                                  |

The path `ISRT` in `8500-INSERT-AUTH` is worth a closer look — it uses
two `SEGMENT` clauses in one `EXEC DLI`, telling IMS "find this parent
and insert under it":

```cobol
EXEC DLI ISRT USING PCB(PAUT-PCB-NUM)
     SEGMENT (PAUTSUM0)
     WHERE (ACCNTID = PA-ACCT-ID)
     SEGMENT (PAUTDTL1)
     FROM (PENDING-AUTH-DETAILS)
     SEGLENGTH (LENGTH OF PENDING-AUTH-DETAILS)
END-EXEC
```

If `8400-UPDATE-SUMMARY` had to do the `ISRT` (no summary existed before),
parent positioning is left valid for the child insert.

**DB2 SQL surface.** None. This program does no DB2 work.

**MQ surface.** `MQOPEN`, `MQGET` (in `3100-READ-REQUEST-MQ`), `MQPUT1`
(in `7100-SEND-RESPONSE`), `MQCLOSE` (in `9100-CLOSE-REQUEST-QUEUE`).
Trigger payload retrieved with `EXEC CICS RETRIEVE INTO(MQTM)`.

**CICS commands.** `RETRIEVE`, `READ` (against `CCXREF`, `ACCTDAT`,
`CUSTDAT`), `ASKTIME`, `FORMATTIME`, `WRITEQ TD QUEUE('CSSL')`,
`SYNCPOINT`, `RETURN`. There are no `LINK`/`XCTL` calls — this program
does not chain to another CICS program.

**Control flow** (paragraphs `MAIN-PARA` → `1000-INITIALIZE` →
`2000-MAIN-PROCESS` → `9000-TERMINATE`):

- `1000-INITIALIZE` — retrieve trigger data, open request queue, prime
  the loop with one `3100-READ-REQUEST-MQ`.
- `2000-MAIN-PROCESS` — loop until no message or 500 processed:
  `2100-EXTRACT-REQUEST-MSG` (UNSTRING the CSV into `CCPAURQY` fields)
  → `5000-PROCESS-AUTH` → `EXEC CICS SYNCPOINT` (commits IMS work and
  uncouples PSB) → next read.
- `5000-PROCESS-AUTH` — orchestrates: schedule PSB, read XREF→ACCT→CUST
  in VSAM, read existing IMS summary (`5500`), make decision (`6000`),
  send response (`7100`), then if XREF was found update IMS
  (`8000-WRITE-AUTH-TO-DB`).
- `6000-MAKE-DECISION` — credit-limit check; chooses
  `PA-CREDIT-LIMIT`/`PA-CREDIT-BALANCE` from IMS summary if it exists,
  else `ACCT-CREDIT-LIMIT`/`ACCT-CURR-BAL` from VSAM. If transaction
  amount exceeds available credit, sets `INSUFFICIENT-FUND`. Builds the
  comma-separated reply with `STRING ... DELIMITED BY SIZE`.
- `8500-INSERT-AUTH` — derives the inverted-date and inverted-time keys
  using the formula `99999 - WS-YYDDD` and `999999999 - WS-TIME-WITH-MS`,
  giving newest-first ordering when IMS sorts ascending.
- `9000-TERMINATE` — `EXEC DLI TERM` if PSB was scheduled; close request
  queue.
- Errors are logged via `9500-LOG-ERROR` to the `CSSL` TD queue. Critical
  errors call `9990-END-ROUTINE` which terminates the PSB and `EXEC CICS
  RETURN`s.

**Notable patterns.**

- **PSB-aware loop.** After every `SYNCPOINT` the program sets
  `IMS-PSB-NOT-SCHD TO TRUE`, because CICS commits the unit of work and
  releases the PSB. The next message's `5000-PROCESS-AUTH` re-issues
  `EXEC DLI SCHD` via `1200-SCHEDULE-PSB`.
- **`PSB-SCHEDULED-MORE-THAN-ONCE` recovery.** If `SCHD` returns `TC`
  (already scheduled), the code does `EXEC DLI TERM` and re-schedules.
  Defence against a previous task leaving the PSB attached.
- **MQ uses `MQGMO-NO-SYNCPOINT`.** The MQ get is *not* part of the CICS
  unit of work. So if the IMS write fails after the MQ message is consumed,
  the message is *gone* — there is no two-phase MQ↔IMS coordination here.
  Acceptable for a simulator; in production this would be a recovery hole.
- **Response built before IMS write.** `7100-SEND-RESPONSE` runs *before*
  `8000-WRITE-AUTH-TO-DB`. The merchant gets the answer even if the IMS
  write later fails. The `SYNCPOINT` after the IMS write commits the
  decision; if it fails the merchant has been told something the issuer's
  database doesn't agree with.
- **Inverted timestamp keys.** `99999 - YYDDD` and `999999999 - HHMMSSmmm`
  are an old IMS trick to physically order the most recent record first
  with no explicit DESCENDING option.

---

### 7.2 `COPAUS0C` — authorization summary screen

File: `app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl` (1033 lines).

**Purpose.** Online BMS screen showing one account's authorization summary
(approved/declined counts, balances) plus a paged 5-row list of pending
authorization details.

**Online vs. batch.** Online, CICS transaction `CPVS`. User-initiated.

**PSB used.** `PSBPAUTB` (PCB#1).

**Key copybooks.** `CIPAUSMY`, `CIPAUDTY`, `COPAU00` (BMS symbolic),
`COCOM01Y` (shared CardDemo COMMAREA), `COTTL01Y`/`CSDAT01Y`/`CSMSG01Y`
(common screen helpers from the core module), `CVACT01Y`/`CVACT02Y`/
`CVACT03Y`/`CVCUS01Y` (account, customer-account-rel, card-xref, customer
record layouts from core).

This program adds a `CDEMO-CPVS-INFO` block to the COMMAREA holding
selection, page navigation and the `CDEMO-CPVS-AUTH-KEYS(5)` array of
8-byte authorization keys for the rows currently visible on screen. It's
the equivalent of stuffing a Java `PageState` POJO into a session
attribute.

**IMS DL/I call surface.**

| Para                  | Function | Segment      | SSA / qualifier                  |
|:----------------------|:---------|:-------------|:---------------------------------|
| `SCHEDULE-PSB`        | `SCHD`   | (PSB)        | `PSB((PSB-NAME))`, `NODHABEND`   |
| `GET-AUTH-SUMMARY`    | `GU`     | `PAUTSUM0`   | `WHERE (ACCNTID = PA-ACCT-ID)`   |
| `GET-AUTHORIZATIONS`  | `GNP`    | `PAUTDTL1`   | (unqualified — get next under parent) |
| `REPOSITION-AUTHORIZATIONS` | `GNP` | `PAUTDTL1` | `WHERE (PAUT9CTS = PA-AUTHORIZATION-KEY)` |

`GNP` (Get Next within Parent) walks the children of the current root.
The program reads them in batches of 5 for the screen. PF8 (page forward)
keeps issuing `GNP`. PF7 (page back) saves a per-page first-key array in
`CDEMO-CPVS-PAUKEY-PREV-PG`, then on PF7 re-positions with a qualified
`GNP` on the saved key.

**DB2 SQL surface.** None.

**MQ surface.** None.

**CICS commands.** `READ` against `CCXREF` (via `CXACAIX` AIX path),
`ACCTDAT`, `CUSTDAT`; `RECEIVE MAP/SEND MAP` for `COPAU00`; `XCTL` to
`COMEN01C` (main menu) on PF3 or to `COPAUS1C` on selecting an auth;
`SYNCPOINT` after PSB use; `RETURN TRANSID(WS-CICS-TRANID)` for
pseudo-conversational continuation.

**Control flow.**

- `MAIN-PARA` — first-time entry initialises COMMAREA and shows the empty
  summary screen; subsequent entries `RECEIVE MAP` and dispatch on
  `EIBAID` (Enter / PF3 / PF7 / PF8 / other).
- `PROCESS-ENTER-KEY` — if account ID present and one of the row-select
  fields (`SEL0001I`..`SEL0005I`) is non-blank, set `CDEMO-CPVS-PAU-SELECTED`
  to the corresponding key from `CDEMO-CPVS-AUTH-KEYS` and `XCTL` to
  `COPAUS1C`.
- `GATHER-DETAILS` — fetch account data, then `GET-AUTH-SUMMARY`, and if
  the summary segment exists, paginate forward.
- `PROCESS-PF7-KEY` / `PROCESS-PF8-KEY` — page back/forward through
  details using saved first-of-page keys.
- `PROCESS-PAGE-FORWARD` — loop calling `GET-AUTHORIZATIONS` (or one
  `REPOSITION-AUTHORIZATIONS` for the first row of a back-paged page),
  populating the 5 row slots via `POPULATE-AUTH-LIST`, recording the
  page's first key in `CDEMO-CPVS-PAUKEY-PREV-PG` and the last key in
  `CDEMO-CPVS-PAUKEY-LAST`.
- `SEND-PAULST-SCREEN` — if PSB still scheduled, `SYNCPOINT` first to
  release IMS resources, then `SEND MAP`.
- Errors set `WS-ERR-FLG` and emit a system-error message in the screen's
  `ERRMSGO` field.

**Notable patterns.**

- **Pseudo-conversational PSB lifecycle.** PSB is scheduled within one
  task, IMS work happens, then `SYNCPOINT` releases the PSB before
  `RETURN`. On re-entry the PSB is freshly scheduled. This is the only
  way IMS works under CICS, and it's why per-page state has to be carried
  in the COMMAREA, not in PSB position.
- **Page navigation by saved keys.** Because the screen is
  pseudo-conversational, IMS positioning is lost between user actions.
  The program simulates positioning by storing each page's first key.
- **Reads VSAM via the CARDAIX path** (`WS-CARDXREFNAME-ACCT-PATH = 'CXACAIX '`)
  to get from account ID to card number (the XREF is keyed by card number;
  the AIX gives by-account access).

---

### 7.3 `COPAUS1C` — authorization detail screen

File: `app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl` (605 lines).

**Purpose.** Online BMS screen showing every field of one selected
authorization. PF5 marks/un-marks fraud (which calls `COPAUS2C` to update
DB2 + the IMS detail segment).

**Online vs. batch.** Online, CICS transaction `CPVD`. User reaches it via
selection ('S' on the summary screen) which causes `COPAUS0C` to `XCTL`
here.

**PSB used.** `PSBPAUTB` (PCB#1).

**Key copybooks.** `COCOM01Y` (with a `CDEMO-CPVD-INFO` block for navigation
and the staged `CDEMO-CPVD-FRAUD-DATA` parameter area), `COPAU01` (BMS
symbolic), `CIPAUSMY`, `CIPAUDTY`, plus the common header/title
copybooks.

**IMS DL/I call surface.**

| Para                  | Function | Segment      | SSA / qualifier                                   |
|:----------------------|:---------|:-------------|:--------------------------------------------------|
| `SCHEDULE-PSB`        | `SCHD`   | (PSB)        |                                                   |
| `READ-AUTH-RECORD`    | `GU`     | `PAUTSUM0`   | `WHERE (ACCNTID = PA-ACCT-ID)`                    |
| `READ-AUTH-RECORD`    | `GNP`    | `PAUTDTL1`   | `WHERE (PAUT9CTS = PA-AUTHORIZATION-KEY)`         |
| `READ-NEXT-AUTH-RECORD` | `GNP`  | `PAUTDTL1`   | (unqualified — next sibling for PF8)              |
| `UPDATE-AUTH-DETAILS` | `REPL`   | `PAUTDTL1`   | (current position; updates fraud flag and date)   |

**DB2 SQL surface.** None *directly*. Fraud insert/update happens in the
LINKed program `COPAUS2C`.

**MQ surface.** None.

**CICS commands.** `RECEIVE MAP/SEND MAP` for `COPAU01`; `LINK
PROGRAM('COPAUS2C') COMMAREA(WS-FRAUD-DATA)` for the fraud action;
`XCTL` to `COPAUS0C` (PF3 back); `SYNCPOINT` and `SYNCPOINT ROLLBACK` for
the fraud-tagging unit of work; `RETURN TRANSID('CPVD')`.

**Control flow.**

- `MAIN-PARA` — first entry calls `PROCESS-ENTER-KEY` to read and display
  the selected auth; subsequent entries dispatch on `EIBAID`.
- `PROCESS-ENTER-KEY` — `READ-AUTH-RECORD` (qualified `GU` then qualified
  `GNP`), then `SYNCPOINT` to release the PSB, then `POPULATE-AUTH-DETAILS`
  to fill the screen.
- `MARK-AUTH-FRAUD` (PF5) — re-reads the auth, **toggles** the fraud flag
  (`PA-FRAUD-CONFIRMED` ↔ `PA-FRAUD-REMOVED`), packs the auth + account/
  customer IDs into a 200-byte staging area, `EXEC CICS LINK`s to
  `COPAUS2C` to do the DB2 work, and only on DB2 success does
  `UPDATE-AUTH-DETAILS` issue `EXEC DLI REPL`. This is the **multi-resource
  coordination** point — see "Notable patterns".
- `PROCESS-PF8-KEY` — `READ-AUTH-RECORD` then unqualified `GNP` to skip to
  the next detail under the same parent.
- `UPDATE-AUTH-DETAILS` — issues `EXEC DLI REPL` and on success
  `SYNCPOINT`s; on failure does `SYNCPOINT ROLLBACK`.
- `SEARCH ALL` is used on `WS-DECLINE-REASON-TAB` to translate the 4-digit
  auth response reason into a human-readable description.

**Notable patterns.**

- **Two-phase commit, simulated.** The fraud action touches *two* recovery
  managers — DB2 (in linked `COPAUS2C`) and IMS (here). The program does:
  1. `LINK COPAUS2C` — this issues the DB2 INSERT/UPDATE, which is a unit
     of work managed by the CICS-DB2 attachment.
  2. If `WS-FRD-UPDT-SUCCESS`, `UPDATE-AUTH-DETAILS` issues `EXEC DLI REPL`,
     and on success calls `TAKE-SYNCPOINT` (which is `EXEC CICS SYNCPOINT`).
  3. The final `SYNCPOINT` is what *commits* both DB2 and IMS — CICS
     coordinates the syncpoint across both resource managers (this is real
     two-phase commit at the CICS level).
  4. If anything fails, `ROLL-BACK` issues `EXEC CICS SYNCPOINT ROLLBACK`,
     which reverses both. The **catch** is that `COPAUS2C` reports its own
     SQL success/failure in a return area — but the SQL is still pending
     until the syncpoint, so a "DB2 success" from `COPAUS2C` only means
     the DB2 driver accepted the insert; durability waits for the
     `SYNCPOINT`.
- **Toggle semantics.** The same PF5 both reports and removes fraud,
  decided by the current value of `PA-AUTH-FRAUD`.

---

### 7.4 `COPAUS2C` — DB2 fraud insert/update

File: `app/app-authorization-ims-db2-mq/cbl/COPAUS2C.cbl` (245 lines).

**Purpose.** Linked-only CICS program that performs the SQL side of a
fraud action — INSERT into `CARDDEMO.AUTHFRDS` if the row is new, UPDATE
if a duplicate-key (`SQLCODE = -803`) is returned.

**Online vs. batch.** Online, no transaction of its own. Linked from
`COPAUS1C` running under transaction `CPVD`. The DB2 plan is `AWS01PLN`
(via `DB2TRAN(CPVDTRAN) ENTRY(AWS01PLN) TRANSID(CPVD)`).

**PSB used.** None — this program does not touch IMS.

**Key copybooks.** `CIPAUDTY` (the auth detail layout, embedded in
DFHCOMMAREA so the caller can pass it directly). The DB2 host-variable
group comes from `EXEC SQL INCLUDE AUTHFRDS` (i.e. the
`dcl/AUTHFRDS.dcl`). `EXEC SQL INCLUDE SQLCA` brings in the SQL
communications area.

**IMS DL/I call surface.** None.

**DB2 SQL surface.**

- `EXEC SQL INSERT INTO CARDDEMO.AUTHFRDS (...) VALUES (...)` — 26
  columns; the `AUTH_TS` value is built with `TIMESTAMP_FORMAT(:AUTH-TS,
  'YY-MM-DD HH24.MI.SSNNNNNN')` so the host variable can be a 26-byte
  character string with embedded separators. `FRAUD_RPT_DATE` is set with
  the SQL `CURRENT DATE` register.
- `EXEC SQL UPDATE CARDDEMO.AUTHFRDS SET AUTH_FRAUD = :AUTH-FRAUD,
  FRAUD_RPT_DATE = CURRENT DATE WHERE CARD_NUM = :CARD-NUM AND AUTH_TS =
  TIMESTAMP_FORMAT(:AUTH-TS, ...)` — only run when the INSERT returned
  `-803` (duplicate key).

The schema name `CARDDEMO` is hard-coded — the README explicitly notes
that environments using a different schema must edit this program.

**MQ surface.** None.

**CICS commands.** `ASKTIME`, `FORMATTIME`, `RETURN`. (No `LINK`/`XCTL`,
no `RECEIVE MAP`.)

**Control flow.**

- `MAIN-PARA` — get current date for `PA-FRAUD-RPT-DATE`; reconstruct the
  authorization timestamp from the inverted `PA-AUTH-DATE-9C` /
  `PA-AUTH-TIME-9C` pair using `999999999 - PA-AUTH-TIME-9C`; copy every
  authorization field into the corresponding DCLGEN host variable.
- INSERT; on `SQLCODE = 0` set `WS-FRD-UPDT-SUCCESS`; on `SQLCODE = -803`
  fall through to `FRAUD-UPDATE`; otherwise format the SQL error into the
  return message.
- `FRAUD-UPDATE` — same logic, with `UPDATE` instead of `INSERT`.
- `EXEC CICS RETURN` back to caller.

**Notable patterns.**

- **No syncpoint inside.** The INSERT/UPDATE is *not* committed here. The
  caller (`COPAUS1C`) controls the syncpoint, so DB2 and IMS work commit
  together.
- **`SQLCODE = -803` upsert.** Idempotent insert: hit the duplicate key,
  fall back to update. Java equivalent: `try { entityManager.persist(); }
  catch (EntityExistsException e) { entityManager.merge(); }`.
- **`MERCHANT-NAME` host variable.** Has the IBM 2-byte length prefix
  (`MERCHANT-NAME-LEN`) for the VARCHAR column. The program sets
  `MERCHANT-NAME-LEN = LENGTH OF PA-MERCHANT-NAME` (always 22) before the
  SQL — meaning trailing spaces are kept. A "real" implementation would
  strip trailing spaces and set the length accordingly.

---

### 7.5 `CBPAUP0C` — expired authorization purge (BMP batch)

File: `app/app-authorization-ims-db2-mq/cbl/CBPAUP0C.cbl` (387 lines).

**Purpose.** Walks every `PAUTSUM0` root, then every `PAUTDTL1` child,
deletes details older than N days, decrements parent counters and amounts
accordingly, and deletes the parent if both counts drop to zero. Issues
periodic IMS checkpoints.

**Online vs. batch.** Batch BMP (Batch Message Processing region) driven
by JCL `jcl/CBPAUP0J.jcl` which runs `PGM=DFSRRC00` with
`PARM='BMP,CBPAUP0C,PSBPAUTB'`.

**PSB used.** `PSBPAUTB`. PCB#2 (`PAUT-PCB-NUM = +2`) because in BMP
mode the I/O-PCB occupies position 1.

**Key copybooks.** `CIPAUSMY`, `CIPAUDTY`. Reads parameters from `SYSIN`
(`PRM-INFO`: expiry days, checkpoint frequency, debug flag).

**IMS DL/I call surface.** All `EXEC DLI` macros (no `CBLTDLI`):

| Para                       | Function | Segment      | SSA              |
|:---------------------------|:---------|:-------------|:-----------------|
| `2000-FIND-NEXT-AUTH-SUMMARY` | `GN`   | `PAUTSUM0`   | (unqualified)    |
| `3000-FIND-NEXT-AUTH-DTL`  | `GNP`    | `PAUTDTL1`   | (unqualified)    |
| `5000-DELETE-AUTH-DTL`     | `DLET`   | `PAUTDTL1`   | (current position) |
| `6000-DELETE-AUTH-SUMMARY` | `DLET`   | `PAUTSUM0`   | (current position) |
| `9000-TAKE-CHECKPOINT`     | `CHKP`   | (PSB)        | `ID(WK-CHKPT-ID)` |

The checkpoint `ID` is `'RMAD'` followed by a 4-digit counter — typical
restart key naming.

**DB2 SQL surface.** None.

**MQ surface.** None.

**CICS commands.** None — it's batch.

**Control flow.**

- `1000-INITIALIZE` — `ACCEPT PRM-INFO FROM SYSIN`, default expiry to 5
  days, checkpoint frequency to 5 summaries, display frequency to 10.
- Outer loop: `2000-FIND-NEXT-AUTH-SUMMARY` (`GN PAUTSUM0`) until
  `END-OF-AUTHDB` (status `GB`).
- Inner loop: `3000-FIND-NEXT-AUTH-DTL` (`GNP PAUTDTL1`) until `GE`/`GB`.
  - `4000-CHECK-IF-EXPIRED` computes age = `CURRENT-YYDDD -
    (99999 - PA-AUTH-DATE-9C)`; if age ≥ expiry days, decrement the
    parent counters and call `5000-DELETE-AUTH-DTL`.
  - The decrement uses `PA-APPROVED-AUTH-CNT` / `PA-APPROVED-AUTH-AMT`
    for approved auths (response `'00'`), `PA-DECLINED-AUTH-CNT` /
    `PA-DECLINED-AUTH-AMT` otherwise.
- After the inner loop, if both counts are ≤ 0 the summary is deleted
  too.
- Every `P-CHKP-FREQ` summaries, `9000-TAKE-CHECKPOINT` issues `EXEC DLI
  CHKP`, which commits IMS work and creates an IMS restart point.
- `9999-ABEND` — set `RETURN-CODE = 16` and `GOBACK`.
- The end-of-run displays count totals.

**Notable patterns.**

- **BMP PCB indexing is `+2`.** Online programs use `+1`. This is the
  one-line reason CICS programs and BMP programs cannot share IMS
  variable definitions.
- **Inverted-date arithmetic.** Real auth date is recovered as
  `99999 - PA-AUTH-DATE-9C` because of the way `COPAUA0C` stored it
  newest-first.
- **`CHKP` is a restart point**, not just a commit. If the job ABENDs,
  IMS can be restarted from the last `CHKP` ID — which is why the
  checkpoint ID has a counter.
- **There is a small bug** in the summary-deletion guard:
  `PA-APPROVED-AUTH-CNT <= 0 AND PA-APPROVED-AUTH-CNT <= 0` — the second
  predicate clearly intends `PA-DECLINED-AUTH-CNT`. Document, do not
  silently fix.

---

### 7.6 `DBUNLDGS` — IMS unload to GSAM (batch DLI)

File: `app/app-authorization-ims-db2-mq/cbl/DBUNLDGS.CBL` (367 lines).

**Purpose.** Unloads the entire `DBPAUTP0` HIDAM database to two GSAM
sequential files (one for roots, one for children) for offsite copy or
migration.

**Online vs. batch.** Batch DLI region. JCL `jcl/UNLDGSAM.JCL` runs
`PARM='DLI,DBUNLDGS,DLIGSAMP,,,,,,,,,,,N'` — i.e. the `DLIGSAMP` PSB,
which has three PCBs.

**PSB used.** `DLIGSAMP` (DB PCB + 2 GSAM PCBs).

**Key copybooks.** `CIPAUSMY`, `CIPAUDTY`, `IMSFUNCS` (IMS function
constants `FUNC-GU` etc), `PAUTBPCB` (DB PCB mask), `PASFLPCB` /
`PADFLPCB` (GSAM PCB masks).

**IMS DL/I call surface.** Uses raw `CALL 'CBLTDLI'` (not `EXEC DLI`):

| Para                            | Function   | PCB used    | SSA              |
|:--------------------------------|:-----------|:------------|:-----------------|
| `2000-FIND-NEXT-AUTH-SUMMARY`   | `FUNC-GN`  | `PAUTBPCB`  | `ROOT-UNQUAL-SSA` (`PAUTSUM0 `) |
| `3000-FIND-NEXT-AUTH-DTL`       | `FUNC-GNP` | `PAUTBPCB`  | `CHILD-UNQUAL-SSA` (`PAUTDTL1 `) |
| `3100-INSERT-PARENT-SEG-GSAM`   | `FUNC-ISRT`| `PASFLPCB`  | (no SSA, GSAM)   |
| `3200-INSERT-CHILD-SEG-GSAM`    | `FUNC-ISRT`| `PADFLPCB`  | (no SSA, GSAM)   |

For each `PAUTSUM0` root retrieved, the program inserts it into the root
GSAM file, then inner-loops `GNP` for each child, inserting each into
the child GSAM file.

**DB2 SQL surface.** None.

**MQ surface.** None.

**CICS commands.** None — entry point is `'DLITCBL'` (the IMS-DL/I main
entry), receiving three PCB masks.

**Control flow.**

- `MAIN-PARA` — `ENTRY 'DLITCBL'` with the three PCB masks; init,
  outer-loop on `2000`, close (no-op since GSAM is closed by IMS).
- `2000-FIND-NEXT-AUTH-SUMMARY` — `FUNC-GN`, on space status copy to
  `OPFIL1-REC`, populate `ROOT-SEG-KEY` from `PA-ACCT-ID`, call the
  parent GSAM insert, then inner-loop `3000` until child-end (`GE`).
- `9999-ABEND` — `RETURN-CODE = 16` and `GOBACK`.

**Notable patterns.**

- **`CBLTDLI` vs `EXEC DLI`.** Both are valid IMS interfaces; `CBLTDLI`
  is the lower-level CALL form, `EXEC DLI` is preprocessed into it. This
  module uses `EXEC DLI` for online/BMP programs and raw `CBLTDLI` for
  pure DLI batch programs that take PCB pointers via `LINKAGE SECTION`
  + `ENTRY 'DLITCBL'`.
- **Three PCBs in one PSB.** `DLIGSAMP` has the DB PCB + two GSAM PCBs.
  The COBOL program receives three pointer masks via `PROCEDURE DIVISION
  USING`. Java analogue: a single method signature taking three
  `Repository` interfaces.
- **GSAM = file-as-database.** It looks like a DB PCB (status, key
  feedback, segment name) but writes to/reads from a flat sequential
  file. The benefit over plain QSAM is restart support via IMS
  checkpointing.

---

### 7.7 `PAUDBLOD` — load IMS from flat files (batch BMP)

File: `app/app-authorization-ims-db2-mq/cbl/PAUDBLOD.CBL` (370 lines).

**Purpose.** Reads two flat sequential files (a root file with one
`PAUTSUM0` per record, and a child file with `(account-id, PAUTDTL1)`
per record), and inserts them into the IMS HIDAM database.

**Online vs. batch.** Batch BMP. JCL `jcl/LOADPADB.JCL` runs
`PARM='BMP,PAUDBLOD,PSBPAUTB'`. Note: even though the file says "load",
the JCL uses the read/write PSB `PSBPAUTB` rather than the `PSBPAUTL`
load PSB — this is an "insert into populated database" rather than an
empty-database initial load.

**PSB used.** `PSBPAUTB`. Receives one DB PCB (plus the I/O-PCB) and
declares them in `LINKAGE SECTION`.

**Key copybooks.** `CIPAUSMY`, `CIPAUDTY`, `IMSFUNCS`, `PAUTBPCB`.

**Files.** Two sequential input files defined via `SELECT INFILE1
ASSIGN TO INFILE1` and `SELECT INFILE2 ASSIGN TO INFILE2`, with
`FILE STATUS` clauses. Records: `INFIL1-REC PIC X(100)`,
`INFIL2-REC` = 11-byte packed `ROOT-SEG-KEY` + 200-byte `CHILD-SEG-REC`.

**IMS DL/I call surface.** All `CALL 'CBLTDLI'`:

| Para                    | Function   | SSA                                   | Description                        |
|:------------------------|:-----------|:--------------------------------------|:-----------------------------------|
| `2100-INSERT-ROOT-SEG`  | `FUNC-ISRT`| `ROOT-UNQUAL-SSA` (`PAUTSUM0 `)       | Insert each root from INFILE1      |
| `3100-INSERT-CHILD-SEG` | `FUNC-GU`  | `ROOT-QUAL-SSA` (`PAUTSUM0(ACCNTID EQ key)`) | Position to parent           |
| `3200-INSERT-IMS-CALL`  | `FUNC-ISRT`| `CHILD-UNQUAL-SSA` (`PAUTDTL1 `)      | Insert child under positioned parent |

`ROOT-QUAL-SSA` is a fully qualified SSA with the operator `EQ` and the
6-byte packed key — a good example of how SSAs are constructed in COBOL
as a fixed-layout record.

**DB2 SQL surface.** None.

**MQ surface.** None.

**CICS commands.** None.

**Control flow.**

- `1000-INITIALIZE` — open INFILE1 and INFILE2.
- Outer loop `2000-READ-ROOT-SEG-FILE` until end-of-file: read INFILE1,
  move into `PENDING-AUTH-SUMMARY`, call `2100-INSERT-ROOT-SEG`.
- Outer loop `3000-READ-CHILD-SEG-FILE` until end-of-file: read INFILE2,
  use the `ROOT-SEG-KEY` to locate the parent (`FUNC-GU` with qualified
  SSA), then `FUNC-ISRT` the child.
- `9999-ABEND` on any unexpected status.

**Notable patterns.**

- **Two pass design.** All roots are inserted first, then all children.
  Children rely on `GU` to position to their parent first — it's expensive
  but it lets the input files be in any order.
- **`II` status is tolerated.** Both `2100-INSERT-ROOT-SEG` and
  `3200-INSERT-IMS-CALL` accept `II` (duplicate insert) without abending,
  treating it as "already loaded — skip". Re-runnable load.
- **JCL loads from `AWS.M2.CARDDEMO.PAUTDB.ROOT.FILEO` and `...CHILD.FILEO`** —
  the very files produced by `PAUDBUNL` (next program). Together they form
  an unload/reload cycle.

---

### 7.8 `PAUDBUNL` — unload IMS to flat files (batch DLI)

File: `app/app-authorization-ims-db2-mq/cbl/PAUDBUNL.CBL` (318 lines).

**Purpose.** Mirror of `PAUDBLOD`: walks the IMS database and writes the
two flat sequential files used as input to a future load.

**Online vs. batch.** Batch DLI. JCL `jcl/UNLDPADB.JCL` runs
`PARM='DLI,PAUDBUNL,PAUTBUNL,,,,,,,,,,,N'`.

**PSB used.** `PAUTBUNL` (`PROCOPT=GOTP`, read-only with override).
Receives one PCB.

**Key copybooks.** `CIPAUSMY`, `CIPAUDTY`, `IMSFUNCS`, `PAUTBPCB`.

**Files.** `SELECT OPFILE1 ASSIGN TO OUTFIL1`, `SELECT OPFILE2 ASSIGN TO
OUTFIL2`. Same record layouts as `PAUDBLOD` consumes.

**IMS DL/I call surface.**

| Para                          | Function   | SSA                  | Description                |
|:------------------------------|:-----------|:---------------------|:---------------------------|
| `2000-FIND-NEXT-AUTH-SUMMARY` | `FUNC-GN`  | `ROOT-UNQUAL-SSA`    | Walk roots                 |
| `3000-FIND-NEXT-AUTH-DTL`     | `FUNC-GNP` | `CHILD-UNQUAL-SSA`   | Walk children of current root |

**DB2 SQL surface.** None.

**MQ surface.** None.

**CICS commands.** None.

**Control flow.**

- `1000-INITIALIZE` — open both output files.
- Outer loop `2000-FIND-NEXT-AUTH-SUMMARY` until status `GB`. Per root:
  copy the segment image to `OPFIL1-REC`, copy `PA-ACCT-ID` into
  `ROOT-SEG-KEY` (only if numeric — defensive), then `WRITE OPFIL1-REC`,
  then inner loop on `3000`.
- Inner loop `3000-FIND-NEXT-AUTH-DTL` until status `GE`. Per child:
  copy to `CHILD-SEG-REC` and `WRITE OPFIL2-REC`.
- `9999-ABEND` on bad status.

**Notable patterns.**

- **Mirror of `DBUNLDGS`.** Same purpose, but the latter uses GSAM
  (IMS-managed sequential) and a multi-PCB PSB; this one uses plain
  QSAM `WRITE` statements and a single-PCB PSB. Two ways to skin the
  same cat — `DBUNLDGS` is preferred when you need IMS checkpoint/restart
  on the unload itself.

---

## 8. Batch jobs (`jcl/`)

### `CBPAUP0J.jcl` — purge expired authorizations

Runs `PGM=DFSRRC00` (the IMS region controller) with
`PARM='BMP,CBPAUP0C,PSBPAUTB'`. The `BMP` keyword tells DFSRRC00 to start
a Batch Message Processing region; the second positional is the program
name; the third is the PSB. Required DDs: `IMS` (PSBLIB+DBDLIB),
`DFSRESLB` (IMS resident library), `STEPLIB` (program load library), and
`SYSIN` containing the parameter line `00,00001,00001,Y` (00 expiry days
= use program default 5, checkpoint freq 1, display freq 1, debug ON).

### `DBPAUTP0.jcl` — vendor unload of `DBPAUTP0`

Two-step job: STEPDEL deletes the prior unload dataset; UNLOAD runs
`PGM=DFSRRC00` with `PARM=(ULU,DFSURGU0,DBPAUTP0)`. `ULU` = utility
region; `DFSURGU0` is IMS's HD Reorganization Unload utility. Output
goes to `DD DFSURGU1` on `AWS.M2.CARDDEMO.IMSDATA.DBPAUTP0`. Reads RECON1/
2/3 datasets to coordinate with IMS recovery. This is an IMS-vendor
unload format, distinct from the application-level unload produced by
`PAUDBUNL`/`DBUNLDGS`.

### `LOADPADB.JCL` — populate IMS from flat files

Single-step BMP running `PAUDBLOD`. DDs `INFILE1` =
`AWS.M2.CARDDEMO.PAUTDB.ROOT.FILEO`, `INFILE2` =
`AWS.M2.CARDDEMO.PAUTDB.CHILD.FILEO`. Use this after a fresh IMS database
allocation to repopulate from a prior unload.

### `UNLDGSAM.JCL` — unload IMS through GSAM

Runs `PARM='DLI,DBUNLDGS,DLIGSAMP,,,,,,,,,,,N'`. Outputs to GSAM-managed
files allocated to DDs `PASFILOP` (`AWS.M2.CARDDEMO.PAUTDB.ROOT.GSAM`)
and `PADFILOP` (`AWS.M2.CARDDEMO.PAUTDB.CHILD.GSAM`). Used when a
restartable, IMS-coordinated unload is needed.

### `UNLDPADB.JCL` — unload IMS through QSAM

Two-step: STEP0 deletes prior unload datasets via IEFBR14, then STEP01
runs `PGM=DFSRRC00 PARM='DLI,PAUDBUNL,PAUTBUNL,,,,,,,,,,,N'`. Outputs
plain QSAM files (`AWS.M2.CARDDEMO.PAUTDB.ROOT.FILEO` LRECL 100,
`...CHILD.FILEO` LRECL 206 — note 206 = 6-byte packed key + 200-byte
child seg). These are the files `LOADPADB.JCL` consumes.

---

## 9. Cross-module wiring

### Reads from core VSAM datasets

`COPAUA0C` and `COPAUS0C` both `EXEC CICS READ` against three core VSAM
datasets:

| Dataset    | Used by                          | Access path                |
|:-----------|:---------------------------------|:---------------------------|
| `CCXREF`   | `COPAUA0C`                       | Direct by 16-byte card num |
| `CXACAIX`  | `COPAUS0C`                       | AIX-by-account-id (alternate index over `CCXREF`) |
| `ACCTDAT`  | `COPAUA0C`, `COPAUS0C`           | Direct by 11-byte account ID |
| `CUSTDAT`  | `COPAUA0C`, `COPAUS0C`           | Direct by 9-byte customer ID |

The record layouts come via the core copybooks `CVACT01Y` (account),
`CVACT03Y` (card xref), `CVCUS01Y` (customer), `CVACT02Y`
(card-account-customer xref).

### COMMAREA / COBOL plumbing inherited from core

The standard `COCOM01Y` (`CARDDEMO-COMMAREA`) is reused for
pseudo-conversational state. `COPAUS0C` and `COPAUS1C` *extend* it by
declaring extra blocks (`CDEMO-CPVS-INFO`, `CDEMO-CPVD-INFO`) at fixed
offsets — fine because the COMMAREA is `OCCURS 1 TO 32767 DEPENDING ON
EIBCALEN` and the underlying group is `PIC X(4096)`.

Other shared copybooks: `COTTL01Y` (screen titles), `CSDAT01Y` (current
date helpers), `CSMSG01Y`/`CSMSG02Y` (common messages and abend
variables), `DFHAID`/`DFHBMSCA` (CICS standard).

### Calls into core programs

`COPAUS0C` does `EXEC CICS XCTL PROGRAM('COMEN01C')` on PF3 to return to
the core main menu. `COPAUS1C` does `XCTL` to `COPAUS0C` on PF3 (within
this module). No core program calls *into* this module — the
authorization extension is a pure consumer of core data and a peer of
the core's online subsystem.

### Independence from `app-vsam-mq` and `app-transaction-type-db2`

This module does **not** integrate with the other two extension modules:

- `app-vsam-mq` defines a *separate* MQ subsystem for inquiries against
  VSAM data; its queues, programs, and CSD groups are disjoint from
  this module's `PAUTH.REQUEST`/`PAUTH.REPLY` queues.
- `app-transaction-type-db2` defines a transaction-type DB2 reference
  table; the schema, plan, and DB2ENTRY name are different from
  `CARDDEMO.AUTHFRDS` and `AWS01PLN`. There is no SQL JOIN across the
  two tables, no shared host-variable area.

The three extension modules are designed to be installed independently;
they share only the core VSAM master data and the standard CardDemo
copybooks.

---

## Appendix A — Quick Java translation cheatsheet

| COBOL construct                             | Java / Spring equivalent                     |
|:--------------------------------------------|:---------------------------------------------|
| `EXEC DLI SCHD PSB((PSB-NAME))`             | Open EntityManager / acquire transaction     |
| `EXEC DLI GU SEGMENT(PAUTSUM0) WHERE (...)`  | `repository.findById(...)`                   |
| `EXEC DLI GNP SEGMENT(PAUTDTL1)`             | Iterate `parent.getDetails()`                |
| `EXEC DLI ISRT SEGMENT(PAUTDTL1) FROM(...)`  | `parent.getDetails().add(detail); persist`   |
| `EXEC DLI REPL`                              | `entityManager.merge(entity)`                |
| `EXEC DLI DLET`                              | `repository.delete(entity)`                  |
| `EXEC DLI CHKP ID(...)`                      | Spring Batch `JobOperator` checkpoint        |
| `EXEC DLI TERM`                              | Close EntityManager                          |
| `EXEC SQL INSERT`                            | `JdbcTemplate.update(...)` / JPA `persist`   |
| `EXEC CICS SYNCPOINT`                        | `transactionManager.commit()`                |
| `EXEC CICS SYNCPOINT ROLLBACK`               | `transactionManager.rollback()`              |
| `MQGET` with `MQGMO-WAIT`                    | `JmsTemplate.receive(timeout)`               |
| `MQPUT1`                                     | `JmsTemplate.convertAndSend(...)`            |
| `EXEC CICS RETRIEVE INTO(MQTM)`              | Read trigger payload from `Message` headers  |
| `PIC S9(11) COMP-3` (packed decimal)         | `BigDecimal` (long-equivalent integer)       |
| `PIC S9(09)V99 COMP-3`                       | `BigDecimal` with scale 2                    |
| `PIC X(16)`                                  | `String` (length 16, may be space-padded)    |
| `OCCURS 5 TIMES`                             | `T[5]` or `List<T>` of size 5                |
| `REDEFINES`                                  | Multiple typed accessors over the same bytes |
| `LINK PROGRAM('COPAUS2C') COMMAREA(...)`     | Synchronous method call on a sibling service |
| `XCTL PROGRAM('COMEN01C')`                   | Hand off control / `forward` to next servlet |

---

## Appendix B — IMS PCB status codes used in this module

From `01 WS-IMS-VARIABLES` in every program:

| Status | 88-level                       | Meaning                                       |
|:-------|:-------------------------------|:----------------------------------------------|
| `'  '` | `STATUS-OK`                    | Success                                       |
| `'FW'` | `STATUS-OK`                    | Wraparound (treated as success)               |
| `'GE'` | `SEGMENT-NOT-FOUND`            | Get failed: no matching segment               |
| `'II'` | `DUPLICATE-SEGMENT-FOUND`      | Insert failed: segment already exists         |
| `'GP'` | `WRONG-PARENTAGE`              | GNP issued without parent positioning         |
| `'GB'` | `END-OF-DB`                    | End of database reached                       |
| `'BA'` | `DATABASE-UNAVAILABLE`         | Database not open (retry)                     |
| `'TC'` | `PSB-SCHEDULED-MORE-THAN-ONCE` | PSB already scheduled (TERM and re-SCHD)      |
| `'TE'` | `COULD-NOT-SCHEDULE-PSB`       | Schedule failed (retry)                       |
| `'BA'`/`'FH'`/`'TE'` | `RETRY-CONDITION`     | Transient condition family                    |
