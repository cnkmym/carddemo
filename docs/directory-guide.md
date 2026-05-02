# Directory Guide

A walk through every directory in the repo. For each one: what it
contains, file types, and a few representative filenames so you know what
you're looking at when you `cd` in.

## Top level

```
mock-cobol-migration/
├── app/                  ← All mainframe source — the project itself
├── samples/              ← Standalone compile-JCL templates and runtime bundles
├── scripts/              ← Bash/AWK helpers for upload, compile, run
├── diagrams/             ← Architecture and screen-flow images
├── README.md             ← Operations & install reference (~24 KB)
├── CONTRIBUTING.md       ← Standard AWS open-source contribution guide
├── CODE_OF_CONDUCT.md    ← Amazon Open Source code of conduct
├── LICENSE               ← Apache License 2.0
└── NOTICE                ← Copyright notice (Amazon.com, Inc.)
```

## `app/` — Core application (and extension modules)

Sub-organized into one set of "core" directories holding the base
VSAM-only application, plus three `app-*/` sibling directories holding
optional extension modules (DB2, MQ, IMS+DB2+MQ).

### Core directories

#### `app/cbl/` — COBOL programs

31 COBOL source files (`.cbl` / `.CBL`) — every executable program in the
core application. Two naming conventions split the files by execution
mode:

- `CO*` — online CICS programs (e.g. `COSGN00C.cbl` sign-on,
  `COMEN01C.cbl` main menu, `COACTUPC.cbl` account update,
  `COCRDLIC.cbl` card list, `COTRN01C.cbl` transaction view).
- `CB*` — batch programs (e.g. `CBACT01C.cbl` account file processor,
  `CBTRN02C.cbl` transaction posting, `CBSTM03A.CBL` statement printer,
  `CBEXPORT.cbl` / `CBIMPORT.cbl` export-import jobs).
- `CSUTLDTC.cbl` is a date-conversion utility shared by both modes.

#### `app/cpy/` — COBOL copybooks

30 reusable record-layout files (`.cpy` / `.CPY`). These are `COPY`-d into
COBOL programs to share data structures.

- `CV*` copybooks define **business records**: `CVACT01Y` accounts,
  `CVCRD01Y` cards, `CVCUS01Y` customers, `CVTRA01Y`–`CVTRA07Y`
  transactions and category balances, `CVEXPORT` export layouts.
- `CO*` and `CS*` copybooks define **screen / control structures**:
  `COSTM01.CPY` statement layout, `CSDAT01Y` date helpers, `CSMSG01Y`
  message text, `CSUSR01Y` user security record.

#### `app/cpy-bms/` — Generated BMS symbolic-map copybooks

17 `.CPY` files, one for each BMS map in `app/bms/` (matching base name —
e.g. `COMEN01.CPY` corresponds to `COMEN01.bms`). These are produced by
the BMS assembler at build time and `COPY`-d by the online programs to
declare the named-field structures of each screen.

#### `app/bms/` — BMS map source

17 `.bms` files defining 3270 screen layouts (CICS Basic Mapping
Support). Examples: `COSGN00.bms` (sign-on screen), `COMEN01.bms` (main
menu), `COACTUP.bms` (account update), `CORPT00.bms` (reports menu).
Each compiles into a load module **and** the matching `cpy-bms/*.CPY`.

#### `app/jcl/` — JCL batch jobs

38 batch job decks (`.jcl` / `.JCL`). The set covers:

- **VSAM file definition / refresh** — `ACCTFILE.jcl`, `CARDFILE.jcl`,
  `CUSTFILE.jcl`, `XREFFILE.jcl`, `TRANBKP.jcl`, `DISCGRP.jcl`,
  `TCATBALF.jcl`, `TRANCATG.jcl`, `TRANTYPE.jcl`, `DUSRSECJ.jcl`.
- **CICS file open/close** — `OPENFIL.jcl`, `CLOSEFIL.jcl`.
- **Core processing** — `POSTTRAN.jcl` (post the day's transactions),
  `INTCALC.jcl` (interest), `COMBTRAN.jcl` (merge daily into history),
  `TRANIDX.jcl` (rebuild AIX), `CREASTMT.JCL` (statement generation),
  `DALYREJS.jcl` (daily rejects).
- **Utilities and tooling** — `CBEXPORT.jcl`, `CBIMPORT.jcl`,
  `CBADMCDJ.jcl`, `DEFGDGB.jcl` / `DEFGDGD.jcl` (GDG definitions),
  `DEFCUST.jcl`, `ESDSRRDS.jcl` (ESDS/RRDS demo), `FTPJCL.JCL`,
  `INTRDRJ1.JCL` / `INTRDRJ2.JCL` (internal reader),
  `READACCT.jcl` / `READCARD.jcl` / `READCUST.jcl` / `READXREF.jcl`,
  `REPTFILE.jcl`, `PRTCATBL.jcl`, `TRANREPT.jcl`, `TXT2PDF1.JCL`,
  `WAITSTEP.jcl`.

#### `app/csd/` — CICS System Definition

Single file: `CARDDEMO.CSD`. Defines all CICS resources for the core
application — transactions, programs, mapsets, files (with
remote/local attributes), and any LSR pools. Loaded into a CICS region
via `DFHCSDUP` (or installed interactively with `CEDA`).

#### `app/proc/` — JCL procedures

Two reusable `.prc` files: `REPROC.prc` and `TRANREPT.prc`. These are
called by individual job decks to avoid duplicating common step
definitions.

#### `app/maclib/` — Assembler macros

Two `.mac` files: `ASMWAIT.mac` (timed-wait macro) and `COCDATFT.mac`
(date-format macro). Included by the assembler programs in `app/asm/`.

#### `app/asm/` — Assembler source

Two HLASM programs: `MVSWAIT.asm` (timer / wait primitive used by
`COBSWAIT.cbl`) and `COBDATFT.asm` (low-level date conversion). Provided
to demonstrate that the application crosses the COBOL ↔ HLASM language
boundary — relevant for transpilers and call-graph analyzers.

#### `app/ctl/` — Control cards

Single file: `REPROCT.ctl`, the parameter input for the `REPROC.prc`
report procedure.

#### `app/catlg/` — Catalog listing

Single file: `LISTCAT.txt` — the captured output of an IDCAMS LISTCAT
showing the VSAM cluster definitions. Useful as documentation of the
intended dataset structure (cluster names, AIX, CI/CA sizes).

#### `app/data/ASCII/` — ASCII seed data

9 `.txt` files in human-readable form: `acctdata.txt`, `carddata.txt`,
`cardxref.txt`, `custdata.txt`, `dailytran.txt`, `discgrp.txt`,
`tcatbal.txt`, `trancatg.txt`, `trantype.txt`. These are the same
records as the EBCDIC files below but in a format you can `cat` or diff
without an EBCDIC viewer. Pipe-delimited / fixed-position layout.

#### `app/data/EBCDIC/` — EBCDIC seed data (binary VSAM input)

13 binary EBCDIC datasets, one per VSAM master, named in mainframe HLQ
form: `AWS.M2.CARDDEMO.ACCDATA.PS`, `AWS.M2.CARDDEMO.ACCTDATA.PS`,
`AWS.M2.CARDDEMO.CARDDATA.PS`, `AWS.M2.CARDDEMO.CARDXREF.PS`,
`AWS.M2.CARDDEMO.CUSTDATA.PS`, `AWS.M2.CARDDEMO.DALYTRAN.PS`,
`AWS.M2.CARDDEMO.DALYTRAN.PS.INIT`, `AWS.M2.CARDDEMO.DISCGRP.PS`,
`AWS.M2.CARDDEMO.EXPORT.DATA.PS`, `AWS.M2.CARDDEMO.TCATBALF.PS`,
`AWS.M2.CARDDEMO.TRANCATG.PS`, `AWS.M2.CARDDEMO.TRANTYPE.PS`,
`AWS.M2.CARDDEMO.USRSEC.PS`. Upload to the mainframe in **binary** mode;
they are then loaded into VSAM clusters by the `*FILE.jcl` jobs.

#### `app/scheduler/` — Job scheduler definitions

Two scheduler-specific descriptions of the batch suite:
`CardDemo.ca7` (CA Workload Automation / CA-7) and `CardDemo.controlm`
(BMC Control-M). Both encode the dependency graph of the JCL jobs in
`app/jcl/`.

### Extension modules

#### `app/app-transaction-type-db2/` — DB2 reference-data module

Adds a CICS-DB2 maintenance UI and a batch loader for transaction-type
reference data. Sub-tree mirrors the core (`cbl/`, `cpy/`, `cpy-bms/`,
`bms/`, `csd/`, `jcl/`, `ctl/`) plus two DB2-specific directories:

- `dcl/` — DB2 host-variable declarations (`DECLARE TABLE`s, SQLCA).
- `ddl/` — `CREATE TABLE` scripts for `TRANSACTION_TYPE` and
  `TRANSACTION_TYPE_CATEGORY`.

Programs include `COTRTUPC` (add/edit), `COTRTLIC` (list), and
`COBTUPDT` (batch update). See `README.md` inside the directory for
installation specifics.

#### `app/app-vsam-mq/` — MQ async inquiry module

Minimal extension — only `cbl/` and `csd/`. Two programs: `CODATE01`
(system date inquiry over MQ, transaction `CDRD`) and `COACCT01`
(account-detail inquiry over MQ, transaction `CDRA`). Demonstrates the
**request/response over MQ** pattern using
`CARDDEMO.REQUEST.QUEUE` / `CARDDEMO.RESPONSE.QUEUE`. Message layouts
are documented in the directory's `README.md`.

#### `app/app-authorization-ims-db2-mq/` — Real-time authorizations

The largest extension. Implements credit card authorization processing
that combines IMS, DB2, and MQ. Sub-tree includes everything in the
core plus:

- `ims/` — DBD and PSB definitions: `DBPAUTP0` (HIDAM primary database),
  `DBPAUTX0` (secondary index), `PSBPAUTB` (batch view), `PSBPAUTL`
  (online view), and supporting members.
- `dcl/` and `ddl/` — DB2 fraud-tracking table (`AUTHFRDS`).
- `data/` — module-specific seed data.

Programs: `COPAUA0C` (auth processor), `COPAUS0C`/`S1C`/`S2C` (summary
and detail screens), `CBPAUP0C` (purge job, scheduled by
`CBPAUP0J.jcl`).

## `samples/` — Compile templates and runtime bundles

#### `samples/jcl/`

Standalone compile-job templates, one per compilation flavor:
`BATCMP.jcl` (batch COBOL), `BMSCMP.jcl` (BMS maps), `CICCMP.jcl`
(online CICS), `CICDBCMP.jcl` (CICS + DB2), `IMSMQCMP.jcl` (IMS + MQ),
plus `LISTCAT.jcl`, `RACFCMDS.jcl`, `REPRTEST.jcl`, `SORTTEST.jcl`.
Adapt these as starting points when adding a new program.

#### `samples/proc/`

Reusable procedure decks: `BUILDBAT.prc`, `BUILDBMS.prc`, `BUILDONL.prc`,
`BLDCIDB2.prc`. Generally `INCLUDE`-d by the JCLs in `samples/jcl/`.

#### `samples/m2/mf/`

`CardDemo_runtime.zip` (~458 KB) — Micro Focus Enterprise Server runtime
bundle of CardDemo. Unpack on a Micro Focus host to run the application
without a real mainframe.

#### `samples/m2/unikix/`

`UniKix_CardDemo_runtime_v1.zip` (~424 KB) — equivalent runtime bundle
for the UniKix platform.

## `scripts/` — Build & deploy helpers

11 scripts plus a 45-file `markers/` subdirectory. All scripts assume an
FTP tunnel to the mainframe is running on `localhost:2121` and exit
early if it is not.

| Script                        | Purpose                                                                                                 |
| ----------------------------- | ------------------------------------------------------------------------------------------------------- |
| `upld_module.sh`              | Pad a source file to 80-column records and PUT it as a PDS member (`AWS.M2.CARDDEMO.<TYPE>(<MEMBER>)`). |
| `pad.awk`                     | One-rule AWK helper used by `upld_module.sh` to right-pad each line to 80 chars.                        |
| `compile_batch.jcl.template`  | JCL template with `ZZZZZZZZ` placeholder; runs `IGYCRCTL` (COBOL compiler) and `HEWL` (link-edit).      |
| `remote_compile.sh`           | Substitutes a member name into the template, then submits it to JES.                                    |
| `local_compile.sh`            | Off-mainframe sanity check using GnuCOBOL (`cobc -I ../cpy/ --std=ibm-strict`).                         |
| `remote_submit.sh`            | Submit a single `.jcl` file to JES over the FTP tunnel.                                                 |
| `remote_refresh.sh`           | Run the data-refresh chain: close CICS files → reload masters → reopen CICS files (11 jobs).            |
| `run_full_batch.sh`           | Full nightly cycle: refresh + post + interest + combine + reindex + reopen.                             |
| `run_posting.sh`              | Subset: refresh + post-transactions + reindex + reopen.                                                 |
| `run_interest_calc.sh`        | Subset: close + interest calc + backup + combine + reindex + reopen.                                    |
| `git-addSrcVersionInfo.sh`    | Stamp a `Ver: CardDemo_v2.0-<git-info>` comment line into a source file (comment char chosen by ext).   |

#### `scripts/markers/`

45 zero-byte files matching source-file basenames (`CBACT01C`,
`CVTRA01Y`, `ACCTFILE`, `RACFCMDS`, …). Used as **Make targets** so the
`make -f Makefile` invocation inside `remote_compile.sh` can detect
which sources have been modified since the last upload and re-upload
only the changed ones.

## `diagrams/` — Visual documentation

12 files (~3.1 MB total). Three groups:

- **Screens** — `Signon-Screen.png`, `Main-Menu.png`, `Admin-Menu.png`.
  Captures of the actual CICS BMS screens.
- **Application flows** — `Application-Flow-User.png`,
  `Application-Flow-Admin.png`. Sequence views of how a user navigates
  the online transactions.
- **Data models** — `CARDDEMO-DataModel.drawio` (editable source for
  the core VSAM model), `db2_model.png` (transaction-type module),
  `ims_model.png` (authorization module).
- **Authorization flows** — `auth_flow.png`, `auth_summary.png`,
  `auth_details.png`, `auth_fraud.png`. All correspond to the
  `app-authorization-ims-db2-mq/` extension.
