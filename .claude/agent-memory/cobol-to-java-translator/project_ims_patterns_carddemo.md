---
name: CardDemo IMS patterns and conventions
description: Recurring IMS database, PSB, and DL/I conventions specific to the CardDemo authorization module
type: project
---

The CardDemo `app-authorization-ims-db2-mq/` module uses these consistent IMS patterns worth recognizing across programs:

- **PSB indexing differs by execution mode.** Online CICS programs (`COPAUA0C`, `COPAUS0C`, `COPAUS1C`) use `PAUT-PCB-NUM = +1`. Batch BMP programs (`CBPAUP0C`) use `+2` because the I/O-PCB occupies position 1 in BMP. Pure DLI batch programs receive PCBs as `LINKAGE SECTION` parameters via `ENTRY 'DLITCBL'`.

- **Same PSB name `PSBPAUTB`** is hard-coded in working storage of both online and BMP programs — the only difference is the PCB offset.

- **Inverted timestamp keys for newest-first ordering.** The `PAUTDTL1` child segment key `PAUT9CTS` is built as `99999 - YYDDD || 999999999 - HHMMSSmmm` so IMS's natural ascending sort yields newest-first. Programs unwind this with `99999 - PA-AUTH-DATE-9C` (in `CBPAUP0C` and `COPAUS2C`).

- **`EXEC DLI` vs `CBLTDLI`.** Online and BMP programs use `EXEC DLI` macros; pure DLI batch programs (`PAUDBLOD`, `PAUDBUNL`, `DBUNLDGS`) use raw `CALL 'CBLTDLI'` with function constants from `cpy/IMSFUNCS.cpy`.

- **Pseudo-conversational PSB lifecycle in CICS.** Programs `EXEC DLI SCHD`, do work, `EXEC CICS SYNCPOINT` to release PSB, and re-schedule on next pseudo-conversational entry. State (positioning, page keys) is carried in the COMMAREA via per-program extensions to `COCOM01Y` (e.g. `CDEMO-CPVS-INFO`, `CDEMO-CPVD-INFO`).

- **`PSB-SCHEDULED-MORE-THAN-ONCE` (`'TC'`) recovery pattern.** All online programs check for `'TC'` after `SCHD`, do `EXEC DLI TERM` and re-`SCHD` if so. Defends against stale PSB attachment from prior aborted task.

- **GSAM PSBs** combine DB and GSAM PCBs in one PSB (`DLIGSAMP` has 1 DB + 2 GSAM). PCB masks `PAUTBPCB`, `PASFLPCB`, `PADFLPCB` are separate copybooks, all with the same field layout but distinct prefix.

**Why:** Every IMS-aware program in this module repeats the same status-code 88-levels (`STATUS-OK '  '`/`'FW'`, `SEGMENT-NOT-FOUND 'GE'`, `END-OF-DB 'GB'`, `DUPLICATE-SEGMENT-FOUND 'II'`, etc.). Recognizing these saves re-explaining them per program.

**How to apply:** When walking through any new IMS program in this codebase, anchor on (1) which PSB and PCB index it uses, (2) which segments are accessed and via which DL/I function code, (3) where the pseudo-conversational `SYNCPOINT` falls. The `99999 -` and `999999999 -` arithmetic is always about timestamp inversion.
