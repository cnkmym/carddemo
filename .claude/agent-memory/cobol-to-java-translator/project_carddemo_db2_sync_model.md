---
name: CardDemo DB2 ↔ VSAM TRANTYPE sync is one-way and batch
description: How the app-transaction-type-db2 extension keeps its DB2 tables synchronised with the core's VSAM TRANTYPE file
type: project
---

In CardDemo, the `app-transaction-type-db2` extension (`CTTU`/`CTLI` online, `MNTTRDB2` batch via `COBTUPDT`) writes only to DB2. The core CardDemo runtime never reads DB2 — it reads VSAM `TRANTYPE`. The bridge is the **`TRANEXTR` JCL** which runs (per its header) once a day:
- `STEP10/STEP20` back up the previous PS files to GDG
- `STEP30` deletes them
- `STEP40/STEP50` use `DSNTIAUL` with inline `SELECT CAST(CONCAT(...) AS CHAR(60))` to write 60-byte fixed-length records to `&HLQ..TRANTYPE.PS` and `&HLQ..TRANCATG.PS`
- The core's reference-data loader then loads PS → VSAM

**Why:** README calls this "Dual Storage Strategy" — DB2 for admin convenience, VSAM for transaction-path performance.

**How to apply:** When asked anything about consistency between admin updates and runtime visibility, remember the answer is "next-day batch." The DB2 tables are NOT live-read by the core. Any Java migration discussion should clarify whether dual-write is preserved or collapsed.
