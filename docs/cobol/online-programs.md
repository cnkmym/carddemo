# Online (CICS) COBOL Programs

This document is a per-program reference for the eighteen `CO*.cbl` source
files in `app/cbl/`. The high-level online-vs-batch distinction is covered
in `docs/architecture.md`; this file zooms in on what each program actually
does, the CICS resources it touches, and the COBOL patterns a transpiler or
analyzer needs to handle.

## How an online program runs

The online side is a classic CICS pseudoconversational application. Each
3270 user action triggers a fresh task: the user types data, presses an AID
key (Enter, PF3, PF8, ...), CICS routes the input to the program named in
the transaction definition, the program runs to completion, and ends with
`EXEC CICS RETURN TRANSID(...) COMMAREA(...)`. The next keystroke restarts
the same program but with the saved `DFHCOMMAREA` re-presented in the
`LINKAGE SECTION`. Every program therefore has to discover whether it is on
its first invocation or a re-entry, and the convention used throughout
CardDemo is `EIBCALEN = 0` for first call, otherwise the commarea is
restored. Most programs further use the `CDEMO-PGM-ENTER` / `CDEMO-PGM-REENTER`
flag inside the commarea (see `app/cpy/COCOM01Y.cpy`).

Cross-program navigation uses `EXEC CICS XCTL PROGRAM(...) COMMAREA(...)`
(transfer of control, no return) rather than `LINK` (subroutine call). The
sign-on, two menus, and every "back to main menu" jump goes through `XCTL`.
Every program loads the IBM-supplied copybooks `DFHAID` (PF-key constants)
and `DFHBMSCA` (BMS attribute bytes), and the application copybooks
`COCOM01Y` (commarea), `COTTL01Y` (screen titles), `CSDAT01Y` (date/time
work area), `CSMSG01Y` (common messages). BMS map I/O uses
`EXEC CICS SEND MAP / RECEIVE MAP`; the symbolic map structures
(e.g. `CCRDLIAI`, `CCRDLIAO`) are pulled in from `app/cpy-bms/CO*.CPY` via
the `COPY <mapset>` statement.

Two distinct program "families" coexist in this directory:

- **Compact / "menu-style" programs** (`COSGN00C`, `COMEN01C`, `COADM01C`,
  `COTRN0xC`, `COBIL00C`, `CORPT00C`, `COUSR0xC`). Single `MAIN-PARA`
  paragraph, simple `EVALUATE EIBAID` dispatch, plain `WS-ERR-FLG` flag.
  ~250–700 lines each.
- **Numbered-paragraph "screen handler" programs** (`COACTVWC`, `COACTUPC`,
  `COCRDLIC`, `COCRDSLC`, `COCRDUPC`). Use a richer pattern: paragraphs are
  numbered (`0000-MAIN`, `1000-PROCESS-INPUTS`, `2000-DECIDE-ACTION`,
  `3000-SEND-MAP`, `9000-READ-...`), they layer their own private commarea
  on top of `CARDDEMO-COMMAREA`, they install an abend handler with
  `EXEC CICS HANDLE ABEND LABEL(ABEND-ROUTINE)`, and they normalize the AID
  byte through a shared paragraph `YYYY-STORE-PFKEY` (copybook
  `app/cpy/CSSTRPFY.cpy`) into the `CCARD-AID-*` 88-level flags defined in
  `app/cpy/CVCRD01Y.cpy`. A transpiler should treat these two styles as
  separate templates.

## Catalog: transaction → program → mapset

Source: `app/csd/CARDDEMO.CSD`.

| TRANSID | Program    | Mapset    | Purpose                                                  |
|---------|------------|-----------|----------------------------------------------------------|
| `CC00`  | `COSGN00C` | `COSGN00` | Sign-on screen                                           |
| `CM00`  | `COMEN01C` | `COMEN01` | Main menu (regular users)                                |
| `CA00`  | `COADM01C` | `COADM01` | Admin menu                                               |
| `CAVW`  | `COACTVWC` | `COACTVW` | Account view                                             |
| `CAUP`  | `COACTUPC` | `COACTUP` | Account + customer update                                |
| `CCLI`  | `COCRDLIC` | `COCRDLI` | Card list (paginated browse)                             |
| `CCDL`  | `COCRDSLC` | `COCRDSL` | Card detail / select                                     |
| `CCUP`  | `COCRDUPC` | `COCRDUP` | Card update                                              |
| `CT00`  | `COTRN00C` | `COTRN00` | Transaction list                                         |
| `CT01`  | `COTRN01C` | `COTRN01` | Transaction view                                         |
| `CT02`  | `COTRN02C` | `COTRN02` | Transaction add                                          |
| `CB00`  | `COBIL00C` | `COBIL00` | Bill payment (pay full balance, write transaction)       |
| `CR00`  | `CORPT00C` | `CORPT00` | Submit transaction-report batch job via TDQ `JOBS`       |
| `CU00`  | `COUSR00C` | `COUSR00` | User list (admin)                                        |
| `CU01`  | `COUSR01C` | `COUSR01` | User add (admin)                                         |
| `CU02`  | `COUSR02C` | `COUSR02` | User update (admin)                                      |
| `CU03`  | `COUSR03C` | `COUSR03` | User delete (admin)                                      |
| n/a     | `COBSWAIT` | n/a       | Batch utility (calls assembler `MVSWAIT` for delays)     |

The shared `CARDDEMO-COMMAREA` (copybook `COCOM01Y`) is 232 bytes and
carries `CDEMO-FROM-TRANID`, `CDEMO-FROM-PROGRAM`, `CDEMO-TO-TRANID`,
`CDEMO-TO-PROGRAM`, `CDEMO-USER-ID`, `CDEMO-USER-TYPE` (`A`/`U` 88-levels
`CDEMO-USRTYP-ADMIN`/`CDEMO-USRTYP-USER`), `CDEMO-PGM-CONTEXT`
(`0`=enter / `1`=re-enter), plus customer / account / card handles. Several
programs append a private 2000-byte extension (`WS-COMMAREA PIC X(2000)`)
after the standard area; they read/write it via reference modification:

```
MOVE DFHCOMMAREA(1:LENGTH OF CARDDEMO-COMMAREA)        TO CARDDEMO-COMMAREA
MOVE DFHCOMMAREA(LENGTH OF CARDDEMO-COMMAREA + 1: ...) TO WS-THIS-PROGCOMMAREA
```

## `COSGN00C` — Sign-on (`CC00`)

**Purpose.** Authenticate the user against the `USRSEC` VSAM file and
`XCTL` to the appropriate menu (`COADM01C` for admins, `COMEN01C`
otherwise).

**CICS transaction code.** `CC00`. Source: `app/cbl/COSGN00C.cbl`.

**BMS map.** `COSGN0A` in mapset `COSGN00` (`app/bms/COSGN00.bms`,
symbolic map `app/cpy-bms/COSGN00.CPY`).

**Key copybooks.** `COCOM01Y` (commarea), `COSGN00` (symbolic map),
`COTTL01Y` (screen title literals), `CSDAT01Y` (date/time work area),
`CSMSG01Y` (`CCDA-MSG-THANK-YOU`, `CCDA-MSG-INVALID-KEY`), `CSUSR01Y`
(`SEC-USER-DATA` layout).

**Files.** Reads `USRSEC` (KSDS, key `SEC-USR-ID PIC X(8)`).

**Programs called.** `EXEC CICS XCTL` to `COADM01C` or `COMEN01C` after a
successful login.

**Control flow** (`MAIN-PARA`). On first call (`EIBCALEN = 0`) the program
clears `COSGN0AO`, positions the cursor on `USERIDL`, sends the empty
sign-on map and returns. On re-entry it `EVALUATE EIBAID`s: `DFHENTER`
runs `PROCESS-ENTER-KEY`; `DFHPF3` displays the "thank you" text via
`SEND-PLAIN-TEXT` (`SEND TEXT` then `EXEC CICS RETURN` with no transid —
ending the conversation); anything else is treated as an invalid key.
`PROCESS-ENTER-KEY` receives the map, validates that user-id and password
are non-blank, then calls `READ-USER-SEC-FILE`. The `USRSEC` `READ` returns
`WS-RESP-CD = 13` for not-found and the program stores the password
comparison plain-text — there is no hashing.

**Notable patterns.** Demonstrates the canonical "no commarea = first
call" idiom; uses `FUNCTION UPPER-CASE` on the user id and password before
comparing; returns with `TRANSID(WS-TRANID)` to keep the conversation
alive; uses `EXEC CICS ASSIGN APPLID(...)` and `SYSID(...)` for the header
fields. Note the dialect-specific `WS-RESP-CD` literal `13` for NOTFND
(rather than `DFHRESP(NOTFND)`).

## `COMEN01C` — Main menu (`CM00`)

**Purpose.** Present the regular-user menu (11 options driven by table
copybook `COMEN02Y`) and `XCTL` to the program for the chosen option.

**CICS transaction code.** `CM00`. Source: `app/cbl/COMEN01C.cbl`.

**BMS map.** `COMEN1A` in mapset `COMEN01` (`app/bms/COMEN01.bms`).

**Key copybooks.** `COCOM01Y`; `COMEN02Y` declares
`CARDDEMO-MAIN-MENU-OPTIONS` — an array of 11 (level-88-style fixed
literal `CDEMO-MENU-OPT-COUNT VALUE 11`) menu rows redefined as
`CDEMO-MENU-OPT OCCURS 12 TIMES` with sub-fields `CDEMO-MENU-OPT-NUM`,
`CDEMO-MENU-OPT-NAME`, `CDEMO-MENU-OPT-PGMNAME`, `CDEMO-MENU-OPT-USRTYPE`.
Also `COTTL01Y`, `CSDAT01Y`, `CSMSG01Y`, `CSUSR01Y`.

**Files.** None directly.

**Programs called.** `EXEC CICS XCTL` to whichever program name is in
`CDEMO-MENU-OPT-PGMNAME(WS-OPTION)`. Special case: option name `COPAUS0C`
is probed first with `EXEC CICS INQUIRE PROGRAM(...) NOHANDLE` and only
called if it exists; option names that begin with `DUMMY` show a "coming
soon" message instead of being launched. Returns to `COSGN00C` on PF3.

**Control flow.** First call: initializes commarea, sends the menu. On
re-entry: receives the map, dispatches on `EIBAID` (`DFHENTER` →
`PROCESS-ENTER-KEY`, `DFHPF3` → return to sign-on). `PROCESS-ENTER-KEY`
right-justifies the option text, pads with `'0'`, and validates the
numeric range. If the user is a regular user but the menu row has
`CDEMO-MENU-OPT-USRTYPE = 'A'`, access is denied. Otherwise the
`EVALUATE TRUE` block dispatches by program name.

**Notable patterns.** Reverse-scan trim:
`PERFORM VARYING WS-IDX FROM LENGTH OF OPTIONI BY -1 UNTIL ...` to find
the first non-blank from the right — used to right-align a 2-byte option.
The `BUILD-MENU-OPTIONS` paragraph is a 12-arm hand-rolled `EVALUATE`
that assigns `WS-MENU-OPT-TXT` to `OPTN001O`, `OPTN002O`, ... — the BMS
symbolic map fields are not subscriptable, so the unrolled switch is
unavoidable in pure COBOL.

## `COADM01C` — Admin menu (`CA00`)

**Purpose.** Identical pattern to `COMEN01C` but for the admin user-type
and a 6-row menu (`CDEMO-ADMIN-OPT-COUNT VALUE 6`) sourced from
`COADM02Y.cpy`.

**CICS transaction code.** `CA00`. Source: `app/cbl/COADM01C.cbl`.

**BMS map.** `COADM1A` in mapset `COADM01`.

**Key copybooks.** `COCOM01Y`, `COADM02Y` (admin menu table — entries
include `COUSR00C`/`COUSR01C`/`COUSR02C`/`COUSR03C` plus optional DB2
programs `COTRTLIC` and `COTRTUPC`), `COADM01`, `COTTL01Y`, `CSDAT01Y`,
`CSMSG01Y`, `CSUSR01Y`.

**Files.** None.

**Programs called.** `EXEC CICS XCTL` to whichever program name is in
`CDEMO-ADMIN-OPT-PGMNAME(WS-OPTION)`. PF3 returns to `COSGN00C`.

**Control flow.** Same first-call / re-entry skeleton as `COMEN01C`.
Notable difference: registers `EXEC CICS HANDLE CONDITION
PGMIDERR(PGMIDERR-ERR-PARA)` so that selecting a menu option whose load
module is not installed lands in `PGMIDERR-ERR-PARA` and shows a green
"is not installed..." message rather than abending.

**Notable patterns.** `HANDLE CONDITION` — a global-style exception
handler installed once and triggered automatically by the next failing
CICS command. A Java analyzer must remember that this implicit branch is
live until cancelled (CardDemo never cancels it inside this program).

## `COACTVWC` — Account view (`CAVW`)

**Purpose.** Accept an 11-digit account id, look up the matching customer
and account records, render the data on the `CACTVWA` map. Read-only.

**CICS transaction code.** `CAVW`. Source: `app/cbl/COACTVWC.cbl`.

**BMS map.** `CACTVWA` in mapset `COACTVW`.

**Key copybooks.** `COCOM01Y`, `COACTVW` (symbolic map), `CVCRD01Y`
(`CC-WORK-AREA`, `CCARD-AID-*`, `CC-ACCT-ID`, `CC-CARD-NUM`),
`CVACT01Y` (`ACCOUNT-RECORD`, 300 bytes), `CVACT02Y` (card layout),
`CVACT03Y` (`CARD-XREF-RECORD`), `CVCUS01Y` (`CUSTOMER-RECORD`, 500
bytes), `COTTL01Y`, `CSDAT01Y`, `CSMSG01Y`, `CSMSG02Y` (`ABEND-DATA`),
`CSUSR01Y`, `CSSTRPFY` (the `YYYY-STORE-PFKEY` paragraph).

**Files.** Reads `CXACAIX` (alternate-index path on `CCXREF`, keyed by
`XREF-ACCT-ID`), then `ACCTDAT`, then `CUSTDAT` — all VSAM KSDS, file
literals declared as `LIT-CARDXREFNAME-ACCT-PATH`,
`LIT-ACCTFILENAME`, `LIT-CUSTFILENAME` (8-char names with trailing
blanks).

**Programs called.** `EXEC CICS XCTL` to `CDEMO-FROM-PROGRAM` (or
`COMEN01C` by default) on PF3.

**Control flow.**
- `0000-MAIN` installs `EXEC CICS HANDLE ABEND LABEL(ABEND-ROUTINE)`,
  splits `DFHCOMMAREA` into `CARDDEMO-COMMAREA` + `WS-THIS-PROGCOMMAREA`,
  calls `YYYY-STORE-PFKEY`, then `EVALUATE TRUE` on `CCARD-AID-*` flags.
- PF3 → `XCTL` back; `CDEMO-PGM-ENTER` → `1000-SEND-MAP` (prompt for
  account id); `CDEMO-PGM-REENTER` → `2000-PROCESS-INPUTS` then
  `9000-READ-ACCT` on success.
- `9000-READ-ACCT` chains three paragraphs: `9200-GETCARDXREF-BYACCT`,
  `9300-GETACCTDATA-BYACCT`, `9400-GETCUSTDATA-BYCUST`. Each reports
  with `EVALUATE WS-RESP-CD WHEN DFHRESP(NORMAL) ... WHEN DFHRESP(NOTFND)
  ...`.
- `COMMON-RETURN` re-concatenates the two commareas into `WS-COMMAREA`
  and `EXEC CICS RETURN TRANSID(LIT-THISTRANID) COMMAREA(WS-COMMAREA)`.

**Notable patterns.** The SSN is reformatted with `STRING ... DELIMITED
BY SIZE` into `nnn-nn-nnnn` shape (a Java analyzer should not assume
`STRING` always concatenates with separators). All currency fields are
`PIC S9(10)V99` (12-digit signed packed-implicit decimal), correctly a
`BigDecimal(scale=2)` in Java. The `WS-CARD-RID` group has both
zoned-numeric (`PIC 9(11)`) and `REDEFINES` alphanumeric forms — needed
because `EXEC CICS READ ... RIDFLD(WS-CARD-RID-ACCT-ID-X)` must pass the
character form for VSAM key matching.

## `COACTUPC` — Account + customer update (`CAUP`)

**Purpose.** The largest online program (4236 lines). Loads the existing
account and customer records, lets the user edit them on screen
`CACTUPA`, runs about a dozen field-level edit paragraphs, asks for
confirmation (PF5), then re-reads under `UPDATE`, performs an
optimistic-lock check, and `REWRITE`s both records.

**CICS transaction code.** `CAUP`. Source: `app/cbl/COACTUPC.cbl`.

**BMS map.** `CACTUPA` in mapset `COACTUP`.

**Key copybooks.** Same set as `COACTVWC` plus `CSLKPCDY` (lookup tables:
US area codes, state codes, state+ZIP-2 combinations, used by the
phone / state / zip edit paragraphs), `CSUTLDWY` (work storage for date
edits), `CSUTLDPY` (procedure copybook with `EDIT-DATE-CCYYMMDD` and
related paragraphs that call assembler/LE service `CSUTLDTC`).

**Files.** Reads `CXACAIX`, `ACCTDAT`, `CUSTDAT`. Re-reads `ACCTDAT` and
`CUSTDAT` with `UPDATE` and `REWRITE`s both. Issues `EXEC CICS SYNCPOINT`
on PF3 success, and `EXEC CICS SYNCPOINT ROLLBACK` if the customer
`REWRITE` fails after the account `REWRITE` succeeded.

**Programs called.** `XCTL` to `CDEMO-FROM-PROGRAM` / `COMEN01C` on PF3.

**Control flow** (`0000-MAIN` `EVALUATE TRUE`):
- `CCARD-AID-PFK03` → `SYNCPOINT` then `XCTL` back.
- `ACUP-DETAILS-NOT-FETCHED AND CDEMO-PGM-ENTER` (or first arrival from
  menu) → `3000-SEND-MAP` to prompt for an account number.
- `ACUP-CHANGES-OKAYED-AND-DONE` / `ACUP-CHANGES-FAILED` → reset and
  re-prompt for a fresh account.
- `WHEN OTHER` → `1000-PROCESS-INPUTS` (`1100-RECEIVE-MAP` →
  `1200-EDIT-MAP-INPUTS`) → `2000-DECIDE-ACTION` → `3000-SEND-MAP`.

**Edit paragraphs.** `1210-EDIT-ACCOUNT`, `1215-EDIT-MANDATORY`,
`1220-EDIT-YESNO`, `1225-EDIT-ALPHA-REQD`, `1230-EDIT-ALPHANUM-REQD`,
`1245-EDIT-NUM-REQD`, `1250-EDIT-SIGNED-9V2`, `1260-EDIT-US-PHONE-NUM`
(itself broken into `EDIT-AREA-CODE` / `EDIT-US-PHONE-PREFIX` /
`EDIT-US-PHONE-LINENUM`), `1265-EDIT-US-SSN`, `1270-EDIT-US-STATE-CD`,
`1275-EDIT-FICO-SCORE`, `1280-EDIT-US-STATE-ZIP-CD`. The EDIT-DATE logic
lives in copybook `CSUTLDPY` and uses `CALL 'CSUTLDTC'` (LE date
service).

**Write logic** (`9600-WRITE-PROCESSING`). Reads both records under
`UPDATE` (record locks). Calls `9700-CHECK-CHANGE-IN-REC` to compare the
locked record against the version the user reviewed (`ACUP-OLD-DETAILS`
vs the freshly read record) — abort if changed. Build new record from
`ACUP-NEW-*` work area, `STRING` together date components into
`yyyy-mm-dd`, `STRING` phone components into `(nnn)nnn-nnnn`. `REWRITE`
both files; rollback on failure.

**Notable patterns.** Optimistic-locking compare paragraph
`9700-CHECK-CHANGE-IN-REC`. Deeply nested `EVALUATE TRUE` for control
flow — each `WHEN` is a state, not a value. Heavy use of group-level
`MOVE` (e.g. `INITIALIZE ACUP-NEW-DETAILS` initializes dozens of
sub-fields). Field-level error flags use `88`-level names like
`FLG-ACCTFILTER-NOT-OK`, `FLG-ACCTFILTER-BLANK`, etc. — Java equivalent
is enums or boolean records per field.

## `COCRDLIC` — Card list (`CCLI`)

**Purpose.** Browse-style paginated list of cards from the `CARDDAT`
file (or restricted to a single account via the `CARDAIX` alternate
index). Seven rows per screen, PF7/PF8 for page up/down, two action
codes per row: `S` to view, `U` to update.

**CICS transaction code.** `CCLI`. Source: `app/cbl/COCRDLIC.cbl`.

**BMS map.** `CCRDLIA` in mapset `COCRDLI`.

**Key copybooks.** `COCOM01Y`, `COCRDLI`, `CVCRD01Y`, `CVACT02Y`
(`CARD-RECORD`), `COTTL01Y`, `CSDAT01Y`, `CSMSG01Y`, `CSUSR01Y`.

**Files.** Browses `CARDDAT` (KSDS) directly, or `CARDAIX` when an
account-id filter is supplied. Uses `STARTBR`, `READNEXT`, `READPREV`,
`ENDBR`. The current page's first/last keys are saved in
`WS-CA-FIRST-CARDKEY` / `WS-CA-LAST-CARDKEY` inside
`WS-THIS-PROGCOMMAREA` so that pagination survives the CICS task end.

**Programs called.** `XCTL` to `COCRDSLC` (`CCDL`) for an `S` selection,
`COCRDUPC` (`CCUP`) for `U`, `COMEN01C` on PF3.

**Control flow.**
- `0000-MAIN` initializes commarea (or merges in the existing one),
  calls `YYYY-STORE-PFKEY`, then `EVALUATE TRUE`:
- PF3 → `XCTL` to `COMEN01C`.
- PF7 + first page → re-read forward from saved start key.
- PF7 + non-first → `9100-READ-BACKWARDS`.
- PF8 + next-page-exists → `9000-READ-FORWARD`.
- Enter + a row marked `'S'` → `XCTL` to `COCRDSLC` with the row's
  account number and card number stuffed into the commarea.
- Enter + a row marked `'U'` → `XCTL` to `COCRDUPC`.
- `9000-READ-FORWARD` does `STARTBR`, then up to 7 `READNEXT`s, then a
  peek-ahead `READNEXT` to set the "next page exists" flag.

**Notable patterns.** Seven-row screen array via `WS-SCREEN-ROWS OCCURS
7 TIMES` and a parallel "selection error flag" array.
`WS-EDIT-SELECT-FLAGS PIC X(7)` redefined as
`WS-EDIT-SELECT OCCURS 7 TIMES PIC X(1)` with 88-levels
`SELECT-OK VALUES 'S','U'`, `VIEW-REQUESTED-ON VALUE 'S'`, etc. The
unrolled 7-arm `EVALUATE` in `1250-SETUP-ARRAY-ATTRIBS` is necessary
because the BMS symbolic-map row fields (`CRDSEL1A`, `CRDSEL2A`, ...)
are not subscriptable. `WS-EDIT-SELECT-COUNTER` is `PIC S9(04) USAGE
COMP-3` — packed decimal — most other counters in this program are
plain `COMP`.

## `COCRDSLC` — Card detail / select (`CCDL`)

**Purpose.** Display the full attributes of a single card (account
number, card number, embossed name, expiry date, CVV, status) given
account and card from the previous screen, or an interactively typed
key.

**CICS transaction code.** `CCDL`. Source: `app/cbl/COCRDSLC.cbl`.

**BMS map.** `CCRDSLA` in mapset `COCRDSL`.

**Key copybooks.** `COCOM01Y`, `COCRDSL`, `CVCRD01Y`, `CVACT02Y`
(`CARD-RECORD`), `CVCUS01Y`, `COTTL01Y`, `CSDAT01Y`, `CSMSG01Y`,
`CSMSG02Y`, `CSUSR01Y`, `CSSTRPFY`.

**Files.** Reads `CARDDAT` (key = card number) for direct lookup or
`CARDAIX` (alternate index keyed by account) when only the account is
known. Paragraphs `9100-GETCARD-BYACCTCARD` and `9150-GETCARD-BYACCT`.

**Programs called.** `XCTL` to `CDEMO-FROM-PROGRAM` / `COMEN01C` on PF3.

**Control flow.** Same `0000-MAIN` template as `COACTVWC`. Three branches
in the `EVALUATE TRUE`: PF3 (exit), `CDEMO-PGM-ENTER AND
CDEMO-FROM-PROGRAM = LIT-CCLISTPGM` (came from card list — keys already
validated, so just `9000-READ-DATA` and `1000-SEND-MAP`),
`CDEMO-PGM-ENTER` from elsewhere (prompt for input),
`CDEMO-PGM-REENTER` (validate and read), and a default
`OTHER → ABEND`.

**Notable patterns.** Demonstrates the "no-input early-render" path:
when arriving from `COCRDLIC`, edit validation is skipped because the
upstream program already validated. The expiry date is parsed by
reference modification of a `PIC X(10)` field redefined into year /
month / day substrings.

## `COCRDUPC` — Card update (`CCUP`)

**Purpose.** Update editable card attributes (embossed name, status,
expiry month + year). Fetches the existing record, allows changes,
asks PF5 to confirm, then optimistically re-reads under `UPDATE` and
`REWRITE`s.

**CICS transaction code.** `CCUP`. Source: `app/cbl/COCRDUPC.cbl`.

**BMS map.** `CCRDUPA` in mapset `COCRDUP`.

**Key copybooks.** `COCOM01Y`, `COCRDUP`, `CVCRD01Y`, `CVACT02Y`,
`CVCUS01Y`, `COTTL01Y`, `CSDAT01Y`, `CSMSG01Y`, `CSMSG02Y`, `CSUSR01Y`.

**Files.** Reads / `REWRITE`s `CARDDAT`. References `CARDAIX` literal
but does not actively browse it.

**Programs called.** `XCTL` to `CDEMO-FROM-PROGRAM` / `COMEN01C` on PF3.

**Control flow.** Same overall structure as `COACTUPC`, scaled down:
`0000-MAIN` with five `WHEN`s — PF3, "came from card list with keys",
"fresh entry, prompt", "post-success re-prompt", and "edit pending
inputs". Edits in paragraphs `1210-EDIT-ACCOUNT`, `1220-EDIT-CARD`,
`1230-EDIT-NAME`, `1240-EDIT-CARDSTATUS`, `1250-EDIT-EXPIRY-MON`,
`1260-EDIT-EXPIRY-YEAR`. `9200-WRITE-PROCESSING` re-reads under
`UPDATE`, runs `9300-CHECK-CHANGE-IN-REC`, and `REWRITE`s.

**Notable patterns.** Same pseudoconversational state machine as
`COACTUPC` — the `CCUP-CHANGES-OK-NOT-CONFIRMED`,
`CCUP-CHANGES-OKAYED-AND-DONE`, `CCUP-CHANGES-FAILED`,
`CCUP-DETAILS-NOT-FETCHED`, `CCUP-SHOW-DETAILS` 88-levels are the
discrete states; PF5 advances "ok-not-confirmed" → "okayed-and-done".
A Java rewrite is naturally a small finite-state machine.

## `COTRN00C` — Transaction list (`CT00`)

**Purpose.** Paginated list of `TRANSACT` rows, ten per screen. Lets the
user mark a row with `S` to view it (`XCTL` to `COTRN01C`).

**CICS transaction code.** `CT00`. Source: `app/cbl/COTRN00C.cbl`.

**BMS map.** `COTRN0A` in mapset `COTRN00`.

**Key copybooks.** `COCOM01Y` (extended in-place with `CDEMO-CT00-INFO`
sub-group: first/last transaction id, page number, next-page flag, and
the selection cursor), `COTRN00`, `CVTRA05Y` (`TRAN-RECORD`, 350-byte
transaction record), `COTTL01Y`, `CSDAT01Y`, `CSMSG01Y`.

**Files.** Reads `TRANSACT` (KSDS, key = `TRAN-ID PIC X(16)`) via
`STARTBR` / `READNEXT` / `READPREV` / `ENDBR`.

**Programs called.** `XCTL` to `COTRN01C` for `S` selection,
`COMEN01C` on PF3, `COSGN00C` if `EIBCALEN = 0`.

**Control flow.** Plain "menu-style" template: `MAIN-PARA` →
`EVALUATE EIBAID` → `PROCESS-ENTER-KEY` (10-arm `EVALUATE TRUE`
checking each `SEL000xI` field for an action code) /
`PROCESS-PF7-KEY` (page back) / `PROCESS-PF8-KEY` (page forward).
`PROCESS-PAGE-FORWARD`/`-BACKWARD` open a browse, read up to 10
records, populate the screen, then close with `ENDBR`.

**Notable patterns.** Two unrolled 10-arm `EVALUATE WS-IDX` switches —
`POPULATE-TRAN-DATA` (writes to `TRNID0nI`/`TDATE0nI`/`TDESC0nI`/
`TAMT00nI`) and `INITIALIZE-TRAN-DATA` (clears those same fields). The
`TRAN-AMT` is `PIC S9(09)V99` rendered with a `+99999999.99` edit
picture. `WS-TIMESTAMP` (from `CSDAT01Y`) is parsed in pieces to derive
the displayed date.

## `COTRN01C` — Transaction view (`CT01`)

**Purpose.** Show a single transaction's full detail given its 16-byte
id. Read-only.

**CICS transaction code.** `CT01`. Source: `app/cbl/COTRN01C.cbl`.

**BMS map.** `COTRN1A` in mapset `COTRN01`.

**Key copybooks.** `COCOM01Y` (extended with `CDEMO-CT01-INFO`),
`COTRN01`, `CVTRA05Y`, `COTTL01Y`, `CSDAT01Y`, `CSMSG01Y`.

**Files.** Reads `TRANSACT` with `UPDATE` (locks the record even though
no rewrite happens — likely a copy-paste from the add/edit programs).

**Programs called.** `XCTL` to `CDEMO-FROM-PROGRAM` / `COMEN01C` on PF3,
`COTRN00C` on PF5.

**Control flow.** First call: if `CDEMO-CT01-TRN-SELECTED` is non-blank
(arrived from list) it auto-runs `PROCESS-ENTER-KEY` to populate the
screen. Otherwise sends an empty form. Re-entry: `EVALUATE EIBAID`
covers `DFHENTER` (validate and read), `DFHPF3` (back), `DFHPF4`
(`CLEAR-CURRENT-SCREEN`), `DFHPF5` (back to list).

**Notable patterns.** Straight CRUD-read pattern — useful as the
simplest "view-by-key" template in the codebase.

## `COTRN02C` — Transaction add (`CT02`)

**Purpose.** Insert a new transaction. Either takes the user's account
id (then resolves a card via `CXACAIX`) or a card number (then resolves
the account via `CCXREF`); validates all fields including dates via the
LE date utility `CSUTLDTC`; finds the highest existing `TRAN-ID` via a
backwards browse, increments by 1, and writes the new record.

**CICS transaction code.** `CT02`. Source: `app/cbl/COTRN02C.cbl`.

**BMS map.** `COTRN2A` in mapset `COTRN02`.

**Key copybooks.** `COCOM01Y` (extended with `CDEMO-CT02-INFO`),
`COTRN02`, `CVTRA05Y`, `CVACT01Y`, `CVACT03Y` (cross-reference layout),
`COTTL01Y`, `CSDAT01Y`, `CSMSG01Y`. Local `CSUTLDTC-PARM` group passes
date / format / result to `CALL 'CSUTLDTC'`.

**Files.** Reads `CXACAIX` (account → card alternate index), `CCXREF`
(card → account base), browses `TRANSACT` backwards to find the max id,
then `WRITE`s a new `TRANSACT` row.

**Programs called.** `CALL 'CSUTLDTC'` (LE date validation, dynamic
call). `XCTL` to `COMEN01C` / `CDEMO-FROM-PROGRAM` on PF3,
`COTRN00C`/`COTRN01C`-style logic for navigation. PF5 → "copy last
transaction's data" (re-fetches the highest-id row and pre-populates the
form).

**Control flow.** `MAIN-PARA` → `EVALUATE EIBAID`. `PROCESS-ENTER-KEY`
runs `VALIDATE-INPUT-KEY-FIELDS` then `VALIDATE-INPUT-DATA-FIELDS`,
finally inspects `CONFIRMI` for `Y`/`N`. On `Y` it calls
`ADD-TRANSACTION`: `STARTBR` with `HIGH-VALUES` for `TRAN-ID`,
`READPREV` once to land on the last record, `ENDBR`, increment, and
`WRITE-TRANSACT-FILE`.

**Notable patterns.** Format checks via reference modification:
`TRNAMTI(1:1) NOT EQUAL '-' AND '+'`, `TRNAMTI(2:8) NOT NUMERIC`, etc.
— a Java analyzer must understand that `OF mapname(start:length)`
denotes a substring of the field, not array subscript. `FUNCTION
NUMVAL-C` is used to convert edited numeric strings (with sign and
decimal) back into a `PIC S9(9)V99` field. `WRITE` returns
`DFHRESP(DUPKEY)` / `DFHRESP(DUPREC)` if the auto-incremented id
collides — both responses are treated identically.

## `COBIL00C` — Bill payment (`CB00`)

**Purpose.** Pay an account's full current balance: read the account,
write a `02 / 2` (BILL PAYMENT - ONLINE) transaction stamped with the
current timestamp, and `REWRITE` the account with the balance subtracted
back to zero.

**CICS transaction code.** `CB00`. Source: `app/cbl/COBIL00C.cbl`.

**BMS map.** `COBIL0A` in mapset `COBIL00`.

**Key copybooks.** `COCOM01Y` (extended with `CDEMO-CB00-INFO`),
`COBIL00`, `CVACT01Y`, `CVACT03Y`, `CVTRA05Y`, `COTTL01Y`, `CSDAT01Y`,
`CSMSG01Y`.

**Files.** Reads `ACCTDAT` with `UPDATE` (locks), reads `CXACAIX` (to
derive the card number for the new transaction record), browses
`TRANSACT` backwards once to compute next id, `WRITE`s `TRANSACT`,
`REWRITE`s `ACCTDAT`.

**Programs called.** `XCTL` to `CDEMO-FROM-PROGRAM` / `COMEN01C` on PF3.
No outbound program calls.

**Control flow.** Standard `MAIN-PARA` → `EVALUATE EIBAID`.
`PROCESS-ENTER-KEY` validates the account id, reads the account
(checks balance > 0), waits for the user to type `Y` in the confirm
field (re-prompted if blank), then atomically (within one task) writes
the transaction and rewrites the account. `GET-CURRENT-TIMESTAMP` calls
`EXEC CICS ASKTIME ABSTIME(WS-ABS-TIME)` then `EXEC CICS FORMATTIME` —
the cleanest example of the CICS time API in the codebase.

**Notable patterns.** Bill-payment merchant fields are hard-coded:
`MOVE 999999999 TO TRAN-MERCHANT-ID`, `MOVE 'BILL PAYMENT' TO
TRAN-MERCHANT-NAME`, `MOVE 'N/A' TO TRAN-MERCHANT-CITY`. There is no
syncpoint or rollback — if `REWRITE` fails after `WRITE` succeeds, the
transaction record exists but the balance stays unchanged. (A migration
should add explicit transaction boundaries.)

## `CORPT00C` — Reports menu (`CR00`)

**Purpose.** Build a JCL job stream for the transaction-report batch
(monthly / yearly / custom date range), submit it through CICS internal
reader by writing the lines to TDQ `JOBS`. The actual report is
generated later by the batch program `CBTRN03C`.

**CICS transaction code.** `CR00`. Source: `app/cbl/CORPT00C.cbl`.

**BMS map.** `CORPT0A` in mapset `CORPT00`.

**Key copybooks.** `COCOM01Y`, `CORPT00`, `CVTRA05Y`, `COTTL01Y`,
`CSDAT01Y`, `CSMSG01Y`. Local `CSUTLDTC-PARM` for date validation.

**Files.** No VSAM I/O. Writes the assembled JCL to extra-partition TDQ
`JOBS` (which is wired to JES INTRDR by the CICS resource definitions).

**Programs called.** `CALL 'CSUTLDTC'` (LE date validation). `XCTL` to
`COMEN01C` on PF3.

**Control flow.** `MAIN-PARA` dispatches `EIBAID`. `PROCESS-ENTER-KEY`
inspects `MONTHLYI`, `YEARLYI`, `CUSTOMI` (radio-button-style mutually
exclusive) to choose the date range. `SUBMIT-JOB-TO-INTRDR` then loops
through the 1000-element `JOB-LINES` array (`JOB-DATA-2 REDEFINES
JOB-DATA-1`) writing each 80-byte line to TDQ until it sees `/*EOF` or
a blank.

**Notable patterns.** `JOB-DATA-1` is a long sequence of `FILLER PIC
X(80) VALUE "...JCL..."` items, then `JOB-DATA-2 REDEFINES JOB-DATA-1`
provides `JOB-LINES OCCURS 1000 TIMES PIC X(80)` for indexed access —
a classic COBOL trick for iterating over a hand-built record list.
`PARM-START-DATE-1`/`-2` and `PARM-END-DATE-1`/`-2` are addressable
slots inside the JCL template that the program patches before
submission. `EXEC CICS WRITEQ TD QUEUE('JOBS')` is what actually
delivers the JCL to JES; CardDemo never uses transient data queues for
anything else.

## `COUSR00C` — User list (`CU00`)

**Purpose.** Admin-only paginated browse of `USRSEC`. Ten users per
screen, action codes `U` (XCTL to `COUSR02C`) and `D` (XCTL to
`COUSR03C`).

**CICS transaction code.** `CU00`. Source: `app/cbl/COUSR00C.cbl`.

**BMS map.** `COUSR0A` in mapset `COUSR00`.

**Key copybooks.** `COCOM01Y` (extended with `CDEMO-CU00-INFO`),
`COUSR00`, `COTTL01Y`, `CSDAT01Y`, `CSMSG01Y`, `CSUSR01Y`. Local
`WS-USER-DATA` declares the on-screen 10-row array
(`USER-REC OCCURS 10 TIMES`).

**Files.** `STARTBR` / `READNEXT` / `READPREV` / `ENDBR` on `USRSEC`.

**Programs called.** `XCTL` to `COUSR02C` for `U`, `COUSR03C` for `D`,
`COADM01C` on PF3.

**Control flow.** Identical template to `COTRN00C` but with 10 rows
instead of 10 transaction rows. `PROCESS-ENTER-KEY` is again the
unrolled 10-arm `EVALUATE TRUE` capturing whichever `SEL000xI` row was
marked. Pagination is `PROCESS-PAGE-FORWARD` / `-BACKWARD` over
`USRSEC`.

**Notable patterns.** The on-screen array is declared as a local
`OCCURS 10 TIMES` group with `USER-SEL`, `USER-ID`, `USER-NAME`,
`USER-TYPE`, but the data is moved field-by-field into the symbolic-map
fields via the unrolled `POPULATE-USER-DATA` switch — same lack of
indexed map fields seen elsewhere.

## `COUSR01C` — User add (`CU01`)

**Purpose.** Insert a new user into `USRSEC` (admin-only).

**CICS transaction code.** `CU01`. Source: `app/cbl/COUSR01C.cbl`.

**BMS map.** `COUSR1A` in mapset `COUSR01`.

**Key copybooks.** `COCOM01Y`, `COUSR01`, `COTTL01Y`, `CSDAT01Y`,
`CSMSG01Y`, `CSUSR01Y` (`SEC-USER-DATA` layout, 88 bytes total —
`SEC-USR-ID PIC X(8)`, `SEC-USR-FNAME X(20)`, `SEC-USR-LNAME X(20)`,
`SEC-USR-PWD X(8)`, `SEC-USR-TYPE X(1)`, `SEC-USR-FILLER X(23)`).

**Files.** `WRITE` to `USRSEC`.

**Programs called.** `XCTL` to `COADM01C` on PF3.

**Control flow.** Compact MAIN: dispatch on `EIBAID` (`DFHENTER`,
`DFHPF3`, `DFHPF4`). `PROCESS-ENTER-KEY` runs a single sequential
`EVALUATE TRUE` that checks each input field for blank and short-circuits
to error display on the first failure. On success, populates
`SEC-USER-DATA` and calls `WRITE-USER-SEC-FILE` which inspects
`DFHRESP(DUPKEY)` and `DFHRESP(DUPREC)` for "already exists" handling.

**Notable patterns.** Demonstrates the simplest possible "insert one
record" online template — a useful baseline for transpiler test cases.

## `COUSR02C` — User update (`CU02`)

**Purpose.** Read a user (`READ` with `UPDATE`), let admin edit fields,
and `REWRITE`.

**CICS transaction code.** `CU02`. Source: `app/cbl/COUSR02C.cbl`.

**BMS map.** `COUSR2A` in mapset `COUSR02`.

**Key copybooks.** `COCOM01Y` (extended with `CDEMO-CU02-INFO`),
`COUSR02`, `COTTL01Y`, `CSDAT01Y`, `CSMSG01Y`, `CSUSR01Y`.

**Files.** `READ ... UPDATE` then `REWRITE` on `USRSEC`.

**Programs called.** `XCTL` to `COADM01C` on PF3 / PF12; PF5 triggers
`UPDATE-USER-INFO` in-place (no XCTL).

**Control flow.** Same compact pattern. First call pre-populates the
form from `CDEMO-CU02-USR-SELECTED` if present (so arriving from the
list jumps straight to "loaded for edit"). PF3 / PF5 both invoke the
update path — PF3 then leaves; PF5 stays. `READ-USER-SEC-FILE` reports
on `DFHRESP(NOTFND)` distinctly.

**Notable patterns.** Reuses the standard `WS-ERR-FLG` Y/N pattern
seen in the menu-style programs. Compare against `COCRDUPC` to see two
different idioms for the same conceptual "fetch / edit / rewrite"
flow — useful as test fixtures.

## `COUSR03C` — User delete (`CU03`)

**Purpose.** Read a user, display its current detail for confirmation,
then `DELETE` from `USRSEC` (admin-only) when PF5 is pressed.

**CICS transaction code.** `CU03`. Source: `app/cbl/COUSR03C.cbl`.

**BMS map.** `COUSR3A` in mapset `COUSR03`.

**Key copybooks.** `COCOM01Y` (extended with `CDEMO-CU03-INFO`),
`COUSR03`, `COTTL01Y`, `CSDAT01Y`, `CSMSG01Y`, `CSUSR01Y`.

**Files.** `READ` then `EXEC CICS DELETE DATASET('USRSEC') RIDFLD(...)`.
This is the only program in the online catalog that issues a CICS
`DELETE`.

**Programs called.** `XCTL` to `COADM01C` on PF3 / PF12.

**Control flow.** First call pre-populates from `CDEMO-CU03-USR-SELECTED`
if present, then `READ-USER-SEC-FILE` loads the record for confirmation.
On re-entry: `DFHENTER` re-reads (refresh), `DFHPF5` calls
`DELETE-USER-INFO` which re-validates and issues the `DELETE`.

**Notable patterns.** The only `EXEC CICS DELETE` in the online layer.
Worth a transpiler test case because the syntax diverges from `READ`/
`WRITE`/`REWRITE` (no `INTO`/`FROM`, just `RIDFLD`+`KEYLENGTH`).

## `COBSWAIT` — Wait utility

**Purpose.** Trivial 41-line batch utility (despite living next to the
online programs). Reads a parameter from `SYSIN`, calls the assembler
module `MVSWAIT` (`app/asm/MVSWAIT.asm`) to wait for the supplied
number of centi-seconds, and stops.

**CICS transaction code.** None — this is a `STOP RUN` batch program,
not a CICS task, despite being filed in `app/cbl/` with the online
programs. Source: `app/cbl/COBSWAIT.cbl`.

**BMS map.** None.

**Key copybooks.** None.

**Files.** Reads `SYSIN`.

**Programs called.** Static `CALL 'MVSWAIT' USING MVSWAIT-TIME` — passes
an 8-digit `PIC 9(8) COMP` (binary fullword). The called module is
written in HLASM (`app/asm/MVSWAIT.asm`) and uses MVS service `STIMER`.

**Control flow.** `ACCEPT PARM-VALUE FROM SYSIN` → `MOVE` to numeric →
`CALL` → `STOP RUN`. Used by `scripts/run_full_batch.sh` and JCL
procedures to insert deterministic delays between dependent steps.

**Notable patterns.** Demonstrates the COBOL ↔ assembler call
boundary. The Java equivalent is a `Thread.sleep(parm * 10L)`. Note the
`PIC 9(8) COMP` parameter is a 4-byte big-endian unsigned integer, not
a `BigDecimal` — this matters when generating call-stub code on a
little-endian JVM.

## Cross-program reference tables

### VSAM files used (online side)

| File / Path | Type      | Used by                                                                |
|-------------|-----------|------------------------------------------------------------------------|
| `USRSEC`    | KSDS      | `COSGN00C` (R), `COUSR00C` (BR), `COUSR01C` (W), `COUSR02C` (RW), `COUSR03C` (RD) |
| `ACCTDAT`   | KSDS      | `COACTVWC` (R), `COACTUPC` (RW), `COBIL00C` (RW)                        |
| `CUSTDAT`   | KSDS      | `COACTVWC` (R), `COACTUPC` (RW)                                         |
| `CARDDAT`   | KSDS      | `COCRDLIC` (BR), `COCRDSLC` (R), `COCRDUPC` (RW)                        |
| `CARDAIX`   | AIX path  | `COCRDLIC` (BR), `COCRDSLC` (R alt-key)                                 |
| `CCXREF`    | KSDS      | `COTRN02C` (R)                                                          |
| `CXACAIX`   | AIX path  | `COTRN02C` (R), `COBIL00C` (R)                                          |
| `TRANSACT`  | KSDS      | `COTRN00C` (BR), `COTRN01C` (R), `COTRN02C` (BR + W), `COBIL00C` (BR + W) |
| TDQ `JOBS`  | extra-part TD | `CORPT00C` (W — submits JCL to JES)                                |

(R=read by key, RW=read-with-update + rewrite, BR=browse via
`STARTBR`/`READNEXT`/`READPREV`, W=write/insert, RD=read-then-delete.)

### XCTL graph

```
COSGN00C ──► COMEN01C  (regular user login)
         └─► COADM01C  (admin login)

COMEN01C ──► COACTVWC, COACTUPC, COCRDLIC, COCRDSLC, COCRDUPC,
             COTRN00C, COTRN01C, COTRN02C, CORPT00C, COBIL00C
         └─► COSGN00C   (PF3)

COADM01C ──► COUSR00C, COUSR01C, COUSR02C, COUSR03C
         └─► COSGN00C   (PF3)

COCRDLIC ──► COCRDSLC (S), COCRDUPC (U), COMEN01C (PF3)
COCRDSLC ──► <prev program> / COMEN01C (PF3)
COCRDUPC ──► <prev program> / COMEN01C (PF3)

COACTVWC ──► <prev program> / COMEN01C (PF3)
COACTUPC ──► <prev program> / COMEN01C (PF3)

COTRN00C ──► COTRN01C (S), COMEN01C (PF3)
COTRN01C ──► <prev program> / COMEN01C (PF3), COTRN00C (PF5)
COTRN02C ──► COMEN01C / <prev program> (PF3)
COBIL00C ──► COMEN01C / <prev program> (PF3)
CORPT00C ──► COMEN01C (PF3)

COUSR00C ──► COUSR02C (U), COUSR03C (D), COADM01C (PF3)
COUSR01C ──► COADM01C (PF3)
COUSR02C ──► COADM01C (PF3 / PF12)
COUSR03C ──► COADM01C (PF3 / PF12)
```

### Recurring COBOL idioms a transpiler must handle

- **Pseudoconversational dispatch.** `IF EIBCALEN = 0 ... ELSE
  EVALUATE EIBAID ...`. The state machine is implicit in the
  presence/absence of the commarea plus the AID byte.
- **Two-layer commarea.** `MOVE DFHCOMMAREA(1:LENGTH OF
  CARDDEMO-COMMAREA) TO CARDDEMO-COMMAREA` followed by a second
  `MOVE` for the program-private extension. Reverse on the way out.
- **AID normalization.** The five "screen-handler" programs route the
  raw `EIBAID` byte through `YYYY-STORE-PFKEY` (in `CSSTRPFY.cpy`)
  into 88-level flags `CCARD-AID-ENTER`, `CCARD-AID-PFK01` ...
  `CCARD-AID-PFK24` (with PF13–PF24 mapped back onto PF01–PF12). The
  smaller "menu-style" programs evaluate `EIBAID` directly against
  `DFHENTER` / `DFHPF3` / etc.
- **Symbolic-map row arrays.** BMS does not generate subscriptable
  groups for repeating screen rows. Every program that paints a list
  contains an unrolled `EVALUATE WS-IDX` over `WHEN 1`...`WHEN 10`
  moving into individually-named fields (`OPTN001O`, `TRNID01I`,
  `USRID01I`, ...).
- **REDEFINES for key handling.** Account / card / customer numeric
  keys are declared `PIC 9(11)` / `PIC 9(16)` / `PIC 9(09)` for
  arithmetic, and `REDEFINES`d to `PIC X(...)` for use as VSAM
  `RIDFLD` operands.
- **`USAGE COMP` / `USAGE COMP-3` mix.** All `WS-RESP-CD` /
  `WS-REAS-CD` are `PIC S9(09) COMP` (4-byte binary). Counters are a
  mix — `COCRDLIC` uses `PIC S9(04) USAGE COMP-3` for
  `WS-EDIT-SELECT-COUNTER` and `PIC S9(04) COMP` for the loop index `I`.
- **`HANDLE ABEND LABEL(...)`.** Set in `0000-MAIN` of every screen-
  handler program and active for the rest of the task. The handler
  paragraph (`ABEND-ROUTINE`) sends the `ABEND-DATA` group from
  `CSMSG02Y` and re-issues `EXEC CICS ABEND ABCODE('9999')`.
- **`FUNCTION CURRENT-DATE`.** Every program uses
  `MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA` and slices the
  resulting 21-byte string via the `CSDAT01Y` overlay structure.
- **VSAM response-code idiom.** `EXEC CICS READ ... RESP(WS-RESP-CD)`
  followed by `EVALUATE WS-RESP-CD WHEN DFHRESP(NORMAL) WHEN DFHRESP(NOTFND)
  WHEN OTHER ...`. `COSGN00C` is the lone outlier that compares against
  the literal `13` instead of the `DFHRESP` macro.
- **Optimistic locking.** `COACTUPC` and `COCRDUPC` save the originally
  read record in `ACUP-OLD-DETAILS` / equivalent, re-read with
  `UPDATE`, then compare field-by-field in `9700-CHECK-CHANGE-IN-REC`
  / `9300-CHECK-CHANGE-IN-REC`. Aborts with rollback if the on-disk
  record changed under the user.
- **Currency.** `PIC S9(10)V99` for account balances and credit
  limits, `PIC S9(09)V99` for transaction amounts. Both are signed
  zoned-decimal in source but displayed via `PIC +99999999.99` /
  `PIC +9999999999.99` edit pictures. Translate to `BigDecimal` with
  `setScale(2, HALF_UP)`.
