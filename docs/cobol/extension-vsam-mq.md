# Extension Module: `app-vsam-mq` (CICS-MQ + VSAM)

A developer reference for the optional MQ-driven inquiry extension under
`app/app-vsam-mq/`. This module ships two small CICS programs that act as
**MQ server transactions**: they are started by a trigger message landing on
a request queue, drain that queue, and write replies back to a per-flow
reply queue. One program (`CODATE01`) returns the system date/time; the
other (`COACCT01`) reads the account VSAM file and returns formatted
account details.

The doc is organised top-down: module overview, MQ resource topology
(from the CSD), wire-level message formats (verified against the actual
COBOL data divisions), then a per-program deep dive of every MQ verb call
site, followed by cross-references into the core CardDemo data layout.

---

## 1. Module overview

| Aspect | Value |
|:-------|:------|
| Path | `app/app-vsam-mq/` |
| Programs | `CODATE01`, `COACCT01` (each `IS INITIAL`, COBOL with embedded `EXEC CICS` and `CALL 'MQxxx'`) |
| Transactions | `CDRD` (date), `CDRA` (account) |
| Style | CICS server-side MQ application using the legacy MQI `CALL` interface (not the `EXEC CICS LINK MQ...` SPI) |
| Trigger | Started via the **CICS-MQ trigger monitor** — programs assume `EXEC CICS RETRIEVE` returns an `MQTM` (MQ Trigger Message) |
| External dependencies | IBM MQ copybooks (`CMQV`, `CMQMDV`, `CMQGMOV`, `CMQPMOV`, `CMQODV`, `CMQTML`); CardDemo copybook `CVACT01Y` (account record); VSAM dataset `ACCTDAT` (logical CICS file name) |

What this module demonstrates for a Java developer: it is a textbook
**JMS message-driven bean** equivalent. The trigger monitor plays the role
of an MDB container, each transaction instance is a "consumer thread" that
reads one trigger event, then loops `MQGET` on the request queue with a
short wait until the queue is drained, performs the business logic, and
`MQPUT`s a reply addressed back to the requester. There is no
pseudo-conversational state — the program runs once per trigger, ends with
`EXEC CICS RETURN`, and is reloaded on the next trigger.

---

## 2. MQ resource topology

### 2.1 What the CSD actually contains

`app/app-vsam-mq/csd/CRDDEMOM.csd` ships only **PROGRAM**, **TRANSACTION**
and **LIBRARY** definitions — there are no `MQCONN`, `MQQUEUE` or
`MQMONITOR` resource entries inside this CSD file.

| Resource | Name | Group | Notes |
|:---------|:-----|:------|:------|
| `PROGRAM` | `COACCT01` | `CARDDEMO` | `EXECKEY(USER)`, `CONCURRENCY(QUASIRENT)`, `API(CICSAPI)`, `EXECUTIONSET(FULLAPI)`, `JVM(NO)`, declared `TRANSID(CDRA)` |
| `PROGRAM` | `CODATE01` | `CARDDEMO` | Same attributes as above, declared `TRANSID(CDRD)` |
| `TRANSACTION` | `CDRA` | `CARDDEMO` | `PROGRAM(COACCT01)`, `TASKDATAKEY(USER)`, `ISOLATE(YES)`, `PRIORITY(1)`, `TRANCLASS(DFHTCL00)`, `ACTION(BACKOUT)` |
| `TRANSACTION` | `CDRD` | `CARDDEMO` | `PROGRAM(CODATE01)`, otherwise identical attributes to `CDRA` |
| `LIBRARY` | `CARDDLIB` | `CARDDEMO` | `DSNAME01(AWS.M2.CARDDEMO.LOADLIB)` — load library that the programs are loaded from |

The `ACTION(BACKOUT)` setting matters: if the task abends mid-flow, CICS
will back out the unit of work, which in conjunction with the
`MQGMO-SYNCPOINT` / `MQPMO-SYNCPOINT` flags used in the source means the
request message is **redelivered** rather than lost.

### 2.2 MQ resources implied (but not defined here)

The README's installation snippets show the MQ side that the CICS admin is
expected to define separately:

```
DEFINE QLOCAL('CARDDEMO.REQUEST.QUEUE') REPLACE
DEFINE QLOCAL('CARDDEMO.RESPONSE.QUEUE') REPLACE
DEFINE MQCONN(MQ01) GROUP(CARDDEMO)
DEFINE MQQUEUE(CARDREQ) GROUP(CARDDEMO) QNAME(CARDDEMO.REQUEST.QUEUE)
DEFINE MQQUEUE(CARDRES) GROUP(CARDDEMO) QNAME(CARDDEMO.RESPONSE.QUEUE)
```

There is no `MQMONITOR` definition shipped — the programs use the
**classic trigger-monitor** model: a trigger event fires when a message
arrives, the trigger monitor (`CKTI`) starts the configured transaction
(`CDRD` or `CDRA`), and the started program calls `EXEC CICS RETRIEVE` to
pick up the trigger message (`MQTM`) describing which queue triggered it.

### 2.3 Queue names — README vs. source: **discrepancy**

The README lists `CARDDEMO.REQUEST.QUEUE` and `CARDDEMO.RESPONSE.QUEUE`,
but the COBOL source uses different literals for the **reply** queues and
adds a hard-coded **error** queue:

| Role | Source literal (`MOVE … TO …`) | Set in |
|:-----|:-------------------------------|:-------|
| Input (request) queue | `MQTM-QNAME` from the trigger message — name not hard-coded | `1000-CONTROL` in both programs |
| Reply queue (`CDRD`) | `'CARD.DEMO.REPLY.DATE'` | `CODATE01.cbl` line ~01470012 |
| Reply queue (`CDRA`) | `'CARD.DEMO.REPLY.ACCT'` | `COACCT01.cbl` line ~01328207 |
| Error queue | `'CARD.DEMO.ERROR'` | `2100-OPEN-ERROR-QUEUE` in both programs |

Treat the README as documentation of the *deployment* expectations and the
COBOL literals as authoritative for what the running program will open.
The reply queues are also **opened by name, not by `MQMD-REPLYTOQ`** — the
program ignores the requester's `ReplyToQ` field even though it does
capture it into `SAVE-REPLY2Q` (which is then unused).

---

## 3. Message formats (verified against source)

### 3.1 Request format — used by both flows

The data definition is identical in both programs (`REQUEST-MSG-COPY`):

```cobol
01 REQUEST-MSG-COPY.
   10 WS-FUNC                      PIC X(04) VALUE SPACES.
   10 WS-KEY                       PIC 9(11) VALUE ZEROES.
   10 WS-FILLER                    PIC X(985) VALUE SPACES.
```

Total: **1000 bytes** (matches `MQ-BUFFER-LENGTH = 1000`).

| Field | Offset | PIC | Java type | Notes |
|:------|:-------|:----|:----------|:------|
| `WS-FUNC` | 0 | `X(04)` | `String` length 4 | `COACCT01` checks for literal `'INQA'`. `CODATE01` does not branch on it. |
| `WS-KEY` | 4 | `9(11)` (zoned) | `long` (or 11-char numeric `String`) | Account ID for `CDRA`; ignored by `CDRD`. |
| `WS-FILLER` | 15 | `X(985)` | unused padding | Pads message to 1000 bytes. |

**Discrepancy with README:** the README documents request messages with a
`REQUEST-TYPE PIC X(4)` plus a `REQUEST-ID PIC X(8)` and (for accounts) an
`ACCOUNT-NUMBER PIC X(11)` — total 23 bytes. The actual layout above does
**not** carry a `REQUEST-ID` and uses a 11-byte zoned numeric for the
account key, padded out to 1000 bytes. Treat the COBOL definition as
authoritative; the README's "REQUEST-TYPE" maps to `WS-FUNC`.

### 3.2 Date reply format (`CDRD`)

`CODATE01` builds the reply with one `STRING` statement in
`4000-PROCESS-REQUEST-REPLY`:

```cobol
STRING  'SYSTEM DATE : ' WS-MMDDYYYY
        'SYSTEM TIME : ' WS-TIME
        DELIMITED BY SIZE
        INTO  REPLY-MESSAGE
END-STRING
```

The reply is a **plain text payload** (`MQMD-FORMAT = MQFMT-STRING`, not a
structured copybook). Effective layout:

| Field | PIC | Length | Example |
|:------|:----|:-------|:--------|
| Literal `'SYSTEM DATE : '` | — | 14 | |
| `WS-MMDDYYYY` | `X(10)` | 10 | `05-02-2026` (`DATESEP('-')`) |
| Literal `'SYSTEM TIME : '` | — | 14 | |
| `WS-TIME` | `X(8)` | 8 | `14:23:51` (default `TIMESEP`) |
| Trailing spaces | — | 954 | Buffer is 1000 bytes |

Again, this differs from the README's structured `DATE-RESPONSE-MSG`
layout (it has no `RESPONSE-TYPE` / `RESPONSE-ID` fields).

### 3.3 Account reply format (`CDRA`)

`COACCT01` populates a labelled record (`WS-ACCT-RESPONSE`) and moves it
into `REPLY-MESSAGE`:

```cobol
01 WS-ACCT-RESPONSE.
   05 WS-ACCT-LBL                PIC X(13)  VALUE 'ACCOUNT ID : '.
   05 WS-ACCT-ID                 PIC 9(11)  VALUE ZEROES.
   05 WS-STATUS-LBL              PIC X(17)  VALUE 'ACCOUNT STATUS : '.
   05 WS-ACCT-ACTIVE-STATUS      PIC X(01)  VALUE SPACES.
   05 WS-CURR-BAL-LBL            PIC X(10)  VALUE 'BALANCE : '.
   05 WS-ACCT-CURR-BAL           PIC S9(10)V99 VALUE ZEROES.
   05 WS-CRDT-LMT-LBL            PIC X(15)  VALUE 'CREDIT LIMIT : '.
   05 WS-ACCT-CREDIT-LIMIT       PIC S9(10)V99 VALUE ZEROES.
   05 WS-CASH-LIMIT-LBL          PIC X(13)  VALUE 'CASH LIMIT : '.
   05 WS-ACCT-CASH-CREDIT-LIMIT  PIC S9(10)V99 VALUE ZEROES.
   05 WS-OPEN-DATE-LBL           PIC X(12)  VALUE 'OPEN DATE : '.
   05 WS-ACCT-OPEN-DATE          PIC X(10)  VALUE SPACES.
   05 WS-EXPR-DATE-LBL           PIC X(12)  VALUE 'EXPR DATE : '.
   05 WS-ACCT-EXPIRAION-DATE     PIC X(10)  VALUE SPACES.
   05 WS-REISSUE-DT-LBL          PIC X(12)  VALUE 'REIS DATE : '.
   05 WS-ACCT-REISSUE-DATE       PIC X(10)  VALUE SPACES.
   05 WS-CURR-CYC-CREDIT-LBL     PIC X(13)  VALUE 'CREDIT BAL : '.
   05 WS-ACCT-CURR-CYC-CREDIT    PIC S9(10)V99 VALUE ZEROES.
   05 WS-CURR-CYC-DEBIT-LBL      PIC X(12)  VALUE 'DEBIT BAL : '.
   05 WS-ACCT-CURR-CYC-DEBIT     PIC S9(10)V99 VALUE ZEROES.
   05 WS-ACCT-GRP-LBL            PIC X(11)  VALUE 'GROUP ID : '.
   05 WS-ACCT-GROUP-ID           PIC X(10)  VALUE SPACES.
```

This expands to the following on-the-wire layout. Each `S9(10)V99`
numeric is sent **as a 12-byte zoned-decimal display field** (because the
group has no `USAGE` clause and is `MOVE`d as a whole), so a typical
balance like `+0000123456.78` appears as the bytes `0000012345678` with
the sign overpunched on the trailing digit:

| Region | Length | Cumulative | Notes |
|:-------|:-------|:-----------|:------|
| Account ID label + value | 13 + 11 | 24 | |
| Status label + value | 17 + 1 | 42 | |
| Balance label + value | 10 + 12 | 64 | `S9(10)V99` zoned, sign on last digit |
| Credit-limit label + value | 15 + 12 | 91 | |
| Cash-limit label + value | 13 + 12 | 116 | |
| Open-date label + value | 12 + 10 | 138 | |
| Expr-date label + value | 12 + 10 | 160 | |
| Reissue-date label + value | 12 + 10 | 182 | |
| Cycle-credit label + value | 13 + 12 | 207 | |
| Cycle-debit label + value | 12 + 12 | 231 | |
| Group-ID label + value | 11 + 10 | 252 | |
| Padding to 1000 in `MQ-BUFFER` | 748 | 1000 | from `MOVE WS-ACCT-RESPONSE TO REPLY-MESSAGE` then `MOVE REPLY-MESSAGE TO MQ-BUFFER` (right-padded with spaces) |

Total `WS-ACCT-RESPONSE` length: **252 bytes**, sent inside a 1000-byte
buffer marked `MQFMT-STRING`. The README's `ACCT-RESPONSE-MSG` with a
single 300-byte `ACCOUNT-DATA` field is a high-level approximation;
the actual on-wire content is the labelled, formatted text above.

The error path returns a `STRING`-built diagnostic instead, e.g.:

```cobol
STRING 'INVALID REQUEST PARAMETERS '
       'ACCT ID : ' WS-KEY
       'FUNCTION : ' WS-FUNC
       DELIMITED BY SIZE
       INTO REPLY-MESSAGE
END-STRING
```

---

## 4. Per-program deep dive

Both programs share a near-identical MQ skeleton. Differences are flagged
explicitly in each subsection. Read `4.1` first for the shared scaffolding.

### 4.1 `CODATE01` — Date inquiry over MQ

#### Purpose

Triggered by a request landing on the date-inquiry request queue. Returns
the current CICS system date and time as a free-form text reply.

#### CICS transaction code

`CDRD` (CSD `TRANSACTION(CDRD) PROGRAM(CODATE01)`).

#### Key copybooks used

All from the IBM MQ COBOL include set, copied into top-level WS items:

| Copybook | Group it populates | Purpose |
|:---------|:-------------------|:--------|
| `CMQV` | `MQ-CONSTANTS` | Named constants (`MQOO-*`, `MQGMO-*`, `MQPMO-*`, `MQCC-OK`, `MQRC-NO-MSG-AVAILABLE`, `MQFMT-STRING`, `MQMI-NONE`, `MQCI-NONE`, `MQCCSI-Q-MGR`, `MQCO-NONE`, …) |
| `CMQMDV` | `MQ-MESSAGE-DESCRIPTOR` | The `MQMD` (message descriptor): `MQMD-MSGID`, `MQMD-CORRELID`, `MQMD-REPLYTOQ`, `MQMD-FORMAT`, `MQMD-CODEDCHARSETID`, … |
| `CMQGMOV` | `MQ-GET-MESSAGE-OPTIONS` | `MQGMO`: `MQGMO-OPTIONS`, `MQGMO-WAITINTERVAL`, … |
| `CMQPMOV` | `MQ-PUT-MESSAGE-OPTIONS` | `MQPMO`: `MQPMO-OPTIONS`, … |
| `CMQODV` | `MQ-OBJECT-DESCRIPTOR` | `MQOD`: `MQOD-OBJECTNAME`, `MQOD-OBJECTQMGRNAME`, … |
| `CMQTML` | `MQ-GET-QUEUE-MESSAGE` | The `MQTM` trigger-message layout used by `EXEC CICS RETRIEVE`, exposing `MQTM-QNAME` |

These copybooks are part of IBM MQ's COBOL bindings; they are not in this
repo and are resolved at compile time from the MQ install's COPY library.

No CardDemo-specific copybooks are used by `CODATE01`.

#### MQ API surface — every call site

`CODATE01` uses the **classic MQI `CALL` interface** (not `EXEC CICS LINK
MQ...`). Notably, **there is no explicit `MQCONN` call**: the program
relies on the implicit CICS-MQ adapter connection — `MQ-HCONN` is left at
zero (its `VALUE 0` initialiser) and MQI accepts that as "use the
connection associated with the current CICS task". `MQDISC` is similarly
omitted; `EXEC CICS RETURN` ends the task and the adapter cleans up.

| Paragraph | MQI verb | Queue | Key options / MQMD fields |
|:----------|:---------|:------|:--------------------------|
| `2100-OPEN-ERROR-QUEUE` | `MQOPEN` | `'CARD.DEMO.ERROR'` | `MQOO-OUTPUT + MQOO-PASS-ALL-CONTEXT + MQOO-FAIL-IF-QUIESCING` |
| `2300-OPEN-INPUT-QUEUE` | `MQOPEN` | `INPUT-QUEUE-NAME` (from `MQTM-QNAME`) | `MQOO-INPUT-SHARED + MQOO-SAVE-ALL-CONTEXT + MQOO-FAIL-IF-QUIESCING` |
| `2400-OPEN-OUTPUT-QUEUE` | `MQOPEN` | `'CARD.DEMO.REPLY.DATE'` | `MQOO-OUTPUT + MQOO-PASS-ALL-CONTEXT + MQOO-FAIL-IF-QUIESCING` |
| `3000-GET-REQUEST` | `MQGET` | `INPUT-QUEUE-HANDLE` | `MQGMO-SYNCPOINT + MQGMO-FAIL-IF-QUIESCING + MQGMO-CONVERT + MQGMO-WAIT`, `MQGMO-WAITINTERVAL = 5000` (5 s); `MQMD-MSGID = MQMI-NONE`, `MQMD-CORRELID = MQCI-NONE` (browse-by-no-criteria); buffer 1000 bytes |
| `4100-PUT-REPLY` | `MQPUT` | `OUTPUT-QUEUE-HANDLE` (reply queue) | `MQPMO-SYNCPOINT + MQPMO-DEFAULT-CONTEXT + MQPMO-FAIL-IF-QUIESCING`; `MQMD-MSGID = SAVE-MSGID` and `MQMD-CORRELID = SAVE-CORELID` (echo back the request's identifiers — see §4.4); `MQMD-FORMAT = MQFMT-STRING`; `MQMD-CODEDCHARSETID = MQCCSI-Q-MGR` |
| `9000-ERROR` (on failure) | `MQPUT` | `ERROR-QUEUE-HANDLE` | Same PMO flags as the reply path; payload is the formatted `MQ-ERR-DISPLAY` block |
| `5000-CLOSE-INPUT-QUEUE` | `MQCLOSE` | `INPUT-QUEUE-HANDLE` | `MQ-OPTIONS = MQCO-NONE` |
| `5100-CLOSE-OUTPUT-QUEUE` | `MQCLOSE` | `OUTPUT-QUEUE-HANDLE` | `MQ-OPTIONS = MQCO-NONE` |
| `5200-CLOSE-ERROR-QUEUE` | `MQCLOSE` | `ERROR-QUEUE-HANDLE` | `MQ-OPTIONS = MQCO-NONE` |

There is **no `MQCONN`, no `MQDISC`, no `MQCMIT`/`MQBACK`**. Commit and
backout are delegated to CICS via the unit-of-work model: the program
calls `EXEC CICS SYNCPOINT` at the top of `4000-MAIN-PROCESS` (between
messages) and relies on `EXEC CICS RETURN` plus `ACTION(BACKOUT)` for
abend handling.

#### VSAM access

None. `CODATE01` reads no datasets. It only calls `EXEC CICS ASKTIME` and
`EXEC CICS FORMATTIME` to obtain the system clock.

#### Control flow

1. **Initialise.** `1000-CONTROL` clears working buffers and opens the
   error queue first (so any subsequent failure has somewhere to write).
2. **Pick up trigger.** `EXEC CICS RETRIEVE INTO(MQTM)` pulls the trigger
   message; `MQTM-QNAME` becomes `INPUT-QUEUE-NAME`. The hard-coded
   `'CARD.DEMO.REPLY.DATE'` is moved into `REPLY-QUEUE-NAME`.
3. **Open queues.** Input queue `MQOO-INPUT-SHARED` (allows other server
   instances to consume in parallel), reply queue `MQOO-OUTPUT`.
4. **Drain loop.** `PERFORM 3000-GET-REQUEST` once, then
   `PERFORM 4000-MAIN-PROCESS UNTIL NO-MORE-MSGS`. Each iteration calls
   `EXEC CICS SYNCPOINT` (committing the previous message + reply
   atomically) and then attempts another `MQGET` with a 5-second wait.
5. **Per-message processing.** `4000-PROCESS-REQUEST-REPLY` calls
   `ASKTIME` / `FORMATTIME`, builds the date/time text, and `4100-PUT-REPLY`
   `MQPUT`s it back to the reply queue with `MQMD-MSGID`/`MQMD-CORRELID`
   echoed from the request.
6. **Termination.** `8000-TERMINATION` closes whichever queues are open
   (driven by `REPLY-QUEUE-OPEN`, `RESP-QUEUE-OPEN`, `ERR-QUEUE-OPEN`
   88-level switches), then `EXEC CICS RETURN` + `GOBACK`.

Error handling: every MQ call's `MQ-CONDITION-CODE` is checked. On any
non-`MQCC-OK`, the program populates `MQ-ERR-DISPLAY` (paragraph name +
return-message + condition + reason + queue), then `PERFORM 9000-ERROR`
(which `MQPUT`s the error block onto `'CARD.DEMO.ERROR'`) and `PERFORM
8000-TERMINATION`. The drain loop terminates normally when `MQGET`
returns `MQRC-NO-MSG-AVAILABLE`, which sets `NO-MORE-MSGS` to true.

The program is `INITIAL` (`PROGRAM-ID. CODATE01 IS INITIAL.`), which means
working storage is freshly initialised on each invocation — important
because the trigger model invokes the program once per trigger event and
the program must not carry state between trigger firings.

#### Notable patterns

- **Shared MQMD reuse.** The single `MQ-MESSAGE-DESCRIPTOR` instance is
  reused for every `MQGET` and `MQPUT`. Before each `MQGET` the program
  resets `MQMD-MSGID = MQMI-NONE` and `MQMD-CORRELID = MQCI-NONE`; before
  each `MQPUT` it restores them from `SAVE-MSGID` / `SAVE-CORELID`.
- **Save-and-echo correlation.** After `MQGET` succeeds, the program saves
  `MQMD-MSGID → SAVE-MSGID`, `MQMD-CORRELID → SAVE-CORELID`,
  `MQMD-REPLYTOQ → SAVE-REPLY2Q`. On `MQPUT`, both `MSGID` and `CORRELID`
  are restored, so the requester can correlate its reply by either field.
  `SAVE-REPLY2Q` is captured but **never used** — the reply queue name is
  hard-coded.
- **Format and code page.** Every reply MQPUT sets `MQMD-FORMAT =
  MQFMT-STRING` and `MQMD-CODEDCHARSETID = MQCCSI-Q-MGR`, telling MQ this
  is text in the queue manager's CCSID; clients that opened with
  `MQGMO-CONVERT` will get it transcoded to their CCSID automatically.
- **No `DFHCOMMAREA`.** The program is started by a trigger, not by
  another transaction, so it does not interact with `DFHCOMMAREA`. Its
  only "input" is the `MQTM` trigger message.

### 4.2 `COACCT01` — Account inquiry over MQ

#### Purpose

Triggered by a request on the account-inquiry request queue. Reads the
account VSAM file by account ID and returns a labelled, fixed-format text
record.

#### CICS transaction code

`CDRA` (CSD `TRANSACTION(CDRA) PROGRAM(COACCT01)`).

#### Key copybooks used

Same six MQ copybooks as `CODATE01` (`CMQV`, `CMQMDV`, `CMQGMOV`,
`CMQPMOV`, `CMQODV`, `CMQTML`), **plus** the CardDemo account record
layout:

| Copybook | Provides | Defined at |
|:---------|:---------|:-----------|
| `CVACT01Y` | `01 ACCOUNT-RECORD.` (300-byte fixed-length record: `ACCT-ID`, `ACCT-ACTIVE-STATUS`, three `S9(10)V99` balance/limit fields, three date fields, two cycle-amount fields, ZIP, group ID, 178-byte trailing FILLER) | `app/cpy/CVACT01Y.cpy` |

#### MQ API surface

Identical structure to `CODATE01` — same paragraphs, same options, same
verbs. Differences:

| Paragraph | Difference vs. `CODATE01` |
|:----------|:--------------------------|
| `1000-CONTROL` | Reply queue literal is `'CARD.DEMO.REPLY.ACCT'` (vs. `.DATE`). The error literal at the `EXEC CICS RETRIEVE` failure path is misspelled as `'CICS RETREIVE'` (sic) — it never reaches a Java migration intact unless preserved verbatim. |
| `4000-PROCESS-REQUEST-REPLY` | Replaces the date-formatting block with a VSAM read (see below) and a multi-branch `EVALUATE` on the file response. |

All other MQ call sites (`MQOPEN` of input/output/error queues, `MQGET`
loop with 5 s wait, `MQPUT` of reply, `MQPUT` of error, three `MQCLOSE`
calls) are byte-for-byte the same as in `CODATE01`.

#### VSAM access

| Aspect | Value |
|:-------|:------|
| Logical CICS file name | `ACCTDAT` (literal `'ACCTDAT '` — note trailing space, padding `LIT-ACCTFILENAME PIC X(8)`) |
| Physical dataset (per repo data layout) | `AWS.M2.CARDDEMO.ACCDATA.PS` (EBCDIC) — see `app/data/EBCDIC/AWS.M2.CARDDEMO.ACCDATA.PS` |
| Access mode | `EXEC CICS READ` (random read, not a browse) |
| Key field | `WS-CARD-RID-ACCT-ID-X` — an 11-byte alphanumeric `REDEFINES` of `WS-CARD-RID-ACCT-ID PIC 9(11)`. The numeric `WS-KEY` from the request is `MOVE`d into the numeric form, then the alpha redefinition is passed as `RIDFLD` |
| Record area | `ACCOUNT-RECORD` (from `CVACT01Y`, 300 bytes) |
| Response handling | `EVALUATE WS-RESP-CD WHEN DFHRESP(NORMAL) / DFHRESP(NOTFND) / WHEN OTHER` |

The `READ` call:

```cobol
EXEC CICS READ
     DATASET   (LIT-ACCTFILENAME)
     RIDFLD    (WS-CARD-RID-ACCT-ID-X)
     KEYLENGTH (LENGTH OF WS-CARD-RID-ACCT-ID-X)
     INTO      (ACCOUNT-RECORD)
     LENGTH    (LENGTH OF ACCOUNT-RECORD)
     RESP      (WS-RESP-CD)
     RESP2     (WS-REAS-CD)
END-EXEC
```

On `DFHRESP(NORMAL)` the program copies the relevant fields from
`ACCOUNT-RECORD` into the labelled `WS-ACCT-RESPONSE` area and PUTs.
On `DFHRESP(NOTFND)` or any other response, an error reply is built and
PUT to the **reply** queue (so the requester always gets *some* response),
and on `WHEN OTHER` an additional error MQPUT is also sent to the error
queue via `9000-ERROR`.

Note: the program does **not** issue an explicit `EXEC CICS OPEN FILE` —
the file is expected to be `OPEN(YES)` in its FCT/FILE definition, so each
`EXEC CICS READ` opens implicitly via standard CICS file management.

#### Control flow

1. Same trigger-pickup and queue-open scaffolding as §4.1.
2. `4000-PROCESS-REQUEST-REPLY` first guards on
   `IF WS-FUNC = 'INQA' AND WS-KEY > ZEROES` — only valid `INQA` requests
   with a non-zero account number proceed to the VSAM read. Anything else
   returns `'INVALID REQUEST PARAMETERS ACCT ID : ... FUNCTION : ...'`.
3. Successful read: copy 11 fields from `ACCOUNT-RECORD` into
   `WS-ACCT-RESPONSE`, `MOVE WS-ACCT-RESPONSE TO REPLY-MESSAGE`, MQPUT.
4. `NOTFND` read: build short error reply, MQPUT.
5. Any other error: build error reply, log to error queue via
   `9000-ERROR`, terminate.
6. Loop continues with the next `MQGET` until `MQRC-NO-MSG-AVAILABLE`.

#### Notable patterns

- **Numeric / alphanumeric key duality via `REDEFINES`.** The classic CICS
  pattern: define the key once as `PIC 9(11)`, redefine it as
  `PIC X(11)`, `MOVE` numbers into the numeric form, and pass the alpha
  form to `RIDFLD`. CICS file APIs treat keys as opaque byte strings, so
  the `REDEFINES` is the bridge.
- **`LENGTH OF` instead of magic numbers.** `KEYLENGTH (LENGTH OF
  WS-CARD-RID-ACCT-ID-X)` and `LENGTH (LENGTH OF ACCOUNT-RECORD)` make
  the call self-describing and resilient if the copybook layout grows.
- **Always-respond contract.** Even on bad input (`NOTFND`, invalid
  function), the program writes a reply to the reply queue, so a waiting
  requester never blocks indefinitely on a missing reply. The error queue
  is reserved for *operational* failures (MQ errors, file errors).
- **`MQ-ERR-DISPLAY` formatting block.** The same 25 + 2 + 25 + 2 + 2 + 2
  + 5 + 2 + 48-byte structured error layout (paragraph name, return
  message, condition code, reason code, queue name) is reused everywhere
  — a useful diagnostic envelope you'd reproduce as a Java
  `MqOperationFailure` record carrying the same five fields.
- **No `DFHCOMMAREA`.** Same as `CODATE01` — trigger-driven, no caller.

---

## 5. Cross-references into the core CardDemo system

| Concern | Resource | Where to find it |
|:--------|:---------|:-----------------|
| Account record layout used by `COACCT01` to materialise replies | Copybook `CVACT01Y` (`01 ACCOUNT-RECORD`, 300 bytes) | `app/cpy/CVACT01Y.cpy` |
| VSAM dataset that backs the CICS logical file `ACCTDAT` | Account master file | EBCDIC source: `app/data/EBCDIC/AWS.M2.CARDDEMO.ACCDATA.PS`; ASCII-converted copy: `app/data/ASCII/acctdata.txt` |
| MQ COBOL bindings (constants, MQMD, MQGMO, MQPMO, MQOD, MQTM) | IBM MQ supplied copybooks `CMQV`, `CMQMDV`, `CMQGMOV`, `CMQPMOV`, `CMQODV`, `CMQTML` | Not in repo — supplied by the MQ install at compile time |
| CICS region wiring for these programs | Programs, transactions, library | `app/app-vsam-mq/csd/CRDDEMOM.csd` |
| Module-level user/operator documentation | Module README | `app/app-vsam-mq/README.md` |
| Higher-level architecture (where this module sits in the wider app) | CardDemo overview / architecture / directory guide | `docs/overview.md`, `docs/architecture.md`, `docs/directory-guide.md` |

### 5.1 What a Java port would look like (sketch)

For a Java developer mapping mental models:

| COBOL concept here | Java / Spring analogue |
|:-------------------|:-----------------------|
| `CDRD` / `CDRA` triggered by trigger monitor | `@JmsListener`-annotated method on a request queue (or Spring Cloud Stream consumer binding); container manages threads instead of CICS reloading the program per trigger |
| `MQGET` loop with 5-second wait | The listener container's poll loop; `WAITINTERVAL` becomes the listener's receive timeout |
| `MQGMO-SYNCPOINT` + `EXEC CICS SYNCPOINT` per iteration + `ACTION(BACKOUT)` | JMS transacted session (or JTA `@Transactional` on the listener) — a thrown exception causes redelivery |
| `EXEC CICS READ DATASET('ACCTDAT')` with key | `AccountRepository.findById(accountId).orElseThrow(...)` |
| `WS-ACCT-RESPONSE` labelled text record | A DTO serialised to a fixed-width text representation, or (more idiomatic) JSON if the protocol can change |
| `S9(10)V99` zoned-decimal balance fields | `BigDecimal` with `scale = 2` — never `double`, never `float` |
| `MQMD-CORRELID` echo from request to reply | JMS `JMSCorrelationID` set on the outgoing message from the inbound message's ID |
| `MQ-ERR-DISPLAY` to `CARD.DEMO.ERROR` | Spring's dead-letter queue / error channel binding |
| `IS INITIAL` (working storage reset per invocation) | Listener method scope — locals are fresh per invocation; do not promote to instance fields |

A faithful port should keep the request-loop, sync-on-each-message, and
echo-correlation-ID semantics intact; those are the load-bearing parts
of the contract that any existing requesters rely on.
