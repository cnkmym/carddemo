# Architecture

This document explains how the pieces of CardDemo fit together. For a
directory-by-directory reference, see [`directory-guide.md`](./directory-guide.md).

## Two execution paths: Online and Batch

A CardDemo deployment runs two distinct workloads against the same VSAM
master data:

### Online (CICS)

User-facing transactions invoked through 3270 terminals. The flow is:

```
3270 terminal
  └─ CICS transaction (e.g. CC00)
       └─ COBOL program (app/cbl/CO*.cbl)
            ├─ BMS map for screen I/O      (app/bms/, app/cpy-bms/)
            ├─ Copybooks for record layout (app/cpy/)
            └─ VSAM master files           (app/data/EBCDIC/*.PS)
```

CICS resource definitions (transactions → programs → mapsets → files) live
in the single `app/csd/CARDDEMO.CSD` file. Online programs follow the
`COxxxxxC` naming convention (e.g. `COSGN00C` = sign-on, `COMEN01C` = main
menu, `COACTUPC` = account update).

### Batch (JCL)

Nightly and on-demand processing jobs submitted to JES. The flow is:

```
JES queue
  └─ JCL job        (app/jcl/*.jcl)
       └─ COBOL program (app/cbl/CB*.cbl, e.g. CBTRN02C, CBSTM03A)
            ├─ Procedures (app/proc/*.prc)
            ├─ Control cards (app/ctl/REPROCT.ctl)
            └─ VSAM master files + sequential datasets
```

Batch programs follow the `CBxxxxxC` naming convention. The full nightly
cycle (refresh masters → post transactions → calculate interest → produce
statements) is captured in `scripts/run_full_batch.sh`, which submits the
JCL jobs in order with sleep gaps between them.

## Core application + three optional extension modules

```
app/
├── (core)                            ← VSAM-only credit card application
│   cbl/  cpy/  cpy-bms/  bms/
│   jcl/  csd/  proc/  ctl/  catlg/
│   maclib/  asm/  data/  scheduler/
│
├── app-transaction-type-db2/         ← Adds: DB2 for ref data
├── app-vsam-mq/                      ← Adds: MQ async inquiries
└── app-authorization-ims-db2-mq/     ← Adds: IMS + DB2 + MQ for auths
```

The **core** in the top-level `app/` subdirectories is a fully working
VSAM-only credit card application. The three sibling `app-*/` directories
are independent extension modules — each demonstrates one or more
additional mainframe technologies layered onto the core. They can be
installed individually.

### `app-transaction-type-db2/` — DB2 integration

Adds a CICS-DB2 administrative module for transaction-type reference data.
Demonstrates static embedded SQL (in `dcl/`), DB2 cursors, and CRUD
operations from CICS. Schema for `TRANSACTION_TYPE` and
`TRANSACTION_TYPE_CATEGORY` tables lives in `ddl/`.

### `app-vsam-mq/` — Async messaging

Minimal extension showing the **request/response over MQ** pattern. Two
transactions (`CDRD` for system date, `CDRA` for account inquiry) put a
request on `CARDDEMO.REQUEST.QUEUE`, a listener satisfies the request from
VSAM, and the reply comes back on `CARDDEMO.RESPONSE.QUEUE`. Useful as a
test fixture for tools that have to recognize MQ APIs.

### `app-authorization-ims-db2-mq/` — Multi-DB authorizations

The most complex extension. Real-time credit card authorization processing
that combines:

- **IMS hierarchical DB** (HIDAM primary `DBPAUTP0`, secondary index
  `DBPAUTX0`) for authorization detail storage,
- **DB2** for fraud tracking (`AUTHFRDS` table),
- **MQ** for the inbound authorization request stream from POS / cloud
  systems.

PSBs in `ims/` define separate batch (`PSBPAUTB`) and online
(`PSBPAUTL`) program views. A purge job (`CBPAUP0J`) cleans expired
authorizations.

## Data flow

Master data for the **core** application lives in VSAM clusters defined in
`app/data/EBCDIC/AWS.M2.CARDDEMO.*.PS` (13 datasets covering accounts,
cards, customers, daily transactions, transaction categories, transaction
types, disclosure groups, user security, and more). These are EBCDIC
binary files — upload to the mainframe in **binary** mode.

The same logical data is also provided in human-readable ASCII under
`app/data/ASCII/*.txt` for inspection and diffing. The ASCII files are
pipe-delimited / fixed-position records and are useful when you're
verifying a converter's output without an EBCDIC viewer.

Batch jobs in `app/jcl/` read and mutate the VSAM masters. The typical
nightly chain is:

1. **Close files in CICS** (`CLOSEFIL.jcl`) so batch can take exclusive
   access.
2. **Refresh masters** from seed data (`ACCTFILE.jcl`, `CARDFILE.jcl`,
   `CUSTFILE.jcl`, `XREFFILE.jcl`, `TRANBKP.jcl`, `DISCGRP.jcl`,
   `TCATBALF.jcl`, `TRANTYPE.jcl`, `DUSRSECJ.jcl`).
3. **Post the day's transactions** (`POSTTRAN.jcl`).
4. **Calculate interest** (`INTCALC.jcl`).
5. **Combine** system + daily transactions and **rebuild the AIX**
   (`COMBTRAN.jcl`, `TRANIDX.jcl`).
6. **Reopen files in CICS** (`OPENFIL.jcl`).

`scripts/run_full_batch.sh` is the executable form of that sequence.

## Build & deploy mechanics (`scripts/`)

The repo is developed off-mainframe (in this directory tree) but compiled
and run **on a real mainframe** reached via an FTP tunnel on `localhost:2121`.
The `scripts/` directory wraps that workflow:

- **Source upload** — `upld_module.sh` pads each source line to exactly 80
  columns using `pad.awk`, then PUTs the file into the appropriate PDS
  member (`AWS.M2.CARDDEMO.<TYPE>(<MEMBER>)`) over FTP. The 80-column
  padding is mandatory because mainframe PDS members are fixed-record-length.
- **Compile** — `remote_compile.sh` substitutes the source filename into
  `compile_batch.jcl.template` (replacing the `ZZZZZZZZ` placeholder),
  then submits the resulting JCL to JES (`quote site filetype=JES; put
  <jcl>`). The template runs `IGYCRCTL` (the IBM COBOL compiler), prints
  the listing, and link-edits with `HEWL`.
- **Run** — `remote_submit.sh` submits any single `.jcl` file. The
  higher-level orchestrators (`run_full_batch.sh`, `run_posting.sh`,
  `run_interest_calc.sh`) submit pre-baked sequences with `sleep` gaps to
  let one job finish before the next starts. `remote_refresh.sh` runs the
  data-refresh portion only.
- **Local sanity check** — `local_compile.sh` invokes GnuCOBOL
  (`cobc -I ../cpy/ --std=ibm-strict`) for a non-mainframe syntax check.
- **Versioning** — `git-addSrcVersionInfo.sh` stamps a `Ver:
  CardDemo_v2.0-...` comment line into a source file, computing the
  revision count from `git log`. The comment-character is chosen by file
  extension (`*` for COBOL, `//*` for JCL, etc.).

`scripts/markers/` holds 45 zero-byte placeholder files matching source
filenames (e.g. `CBACT01C`, `ACCTFILE`, `CVTRA01Y`). These are Make
targets — the `make -f Makefile` call inside `remote_compile.sh` uses
them to track which sources have been uploaded since the last edit, so
only changed files get re-uploaded.

## Sample runtimes (`samples/`)

Pre-packaged runnable artifacts for two non-mainframe execution
environments live in `samples/m2/`:

- **`samples/m2/mf/CardDemo_runtime.zip`** — Micro Focus Enterprise
  Server runtime bundle.
- **`samples/m2/unikix/UniKix_CardDemo_runtime_v1.zip`** — UniKix runtime
  bundle.

Use either if you need to run CardDemo without booking time on a real LPAR
— useful for migration validation against an emulator before targeting
real iron. `samples/jcl/` and `samples/proc/` hold standalone compile-job
templates (`BATCMP.jcl`, `BMSCMP.jcl`, `CICCMP.jcl`, `CICDBCMP.jcl`,
`IMSMQCMP.jcl`, plus `BUILDBAT.prc`, `BUILDBMS.prc`, `BUILDONL.prc`,
`BLDCIDB2.prc`) for each compilation flavor (batch, BMS, online CICS,
CICS+DB2, IMS+MQ).

## Reference diagrams (`diagrams/`)

Visual documentation that supplements this architecture writeup:

- **Application flows** — `Application-Flow-User.png`,
  `Application-Flow-Admin.png` show end-to-end transaction sequences for
  each user role.
- **Data models** — `db2_model.png` and `ims_model.png` for the two
  optional databases; `CARDDEMO-DataModel.drawio` is the editable source
  for the core VSAM model.
- **Authorization flows** — `auth_flow.png`, `auth_summary.png`,
  `auth_details.png`, `auth_fraud.png` correspond to the
  `app-authorization-ims-db2-mq/` extension.
- **Screen captures** — `Signon-Screen.png`, `Main-Menu.png`,
  `Admin-Menu.png`.
