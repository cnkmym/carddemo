---
name: CardDemo extension module structure
description: How the CardDemo three optional extension modules relate to the core VSAM application
type: project
---

CardDemo (originally an AWS-published Apache 2.0 sample) lives at the repo root. The codebase is organized as a core VSAM-only credit-card application plus three optional extension modules under `app/`:

- `app-transaction-type-db2/` — adds DB2 reference table for transaction types
- `app-vsam-mq/` — adds MQ-based async inquiry layer over VSAM
- `app-authorization-ims-db2-mq/` — adds real-time authorization processing using IMS hierarchical DB + DB2 fraud table + MQ request/response queues; the largest extension

**Why:** The user asked for per-extension documentation. The three are *independent* — they share core VSAM master data and copybooks (`COCOM01Y`, `CVACT01Y`, `CVACT03Y`, `CVCUS01Y`, screen helpers) but have disjoint queues, plans, schemas, and CSD groups.

**How to apply:** When asked about any module-specific behavior, confirm which module the program lives in and treat extensions as *peers of* the core, not as enrichments to it. The auth extension's `COPAUA0C` *consumes* core VSAM via `EXEC CICS READ` but is never called *from* core programs.
