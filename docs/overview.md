# Overview

## What CardDemo is

CardDemo is a complete **mainframe credit card management application** —
accounts, cards, customers, transactions, statements, and (optionally)
real-time authorizations. It models the kind of system a bank or card issuer
would actually run on z/OS: COBOL business logic, CICS for the online tier,
JCL-driven batch for nightly cycles, VSAM for master data, and RACF for
security.

The application originated as an **AWS-published open-source sample**
(Apache 2.0, copyright Amazon.com, Inc. — see `LICENSE` and `NOTICE` at the
repo root). The root `README.md` retains the upstream operations
documentation. This repo is a working copy used for migration-tooling
exercises; the source itself is unmodified mainframe code.

## Why this repo exists

CardDemo was built specifically as a **reference workload for mainframe
modernization**. From the upstream README:

> CardDemo is a mainframe application designed to test and showcase AWS and
> partner technology for mainframe migration and modernization use-cases. It
> intentionally incorporates various coding styles and patterns to exercise
> analysis, transformation, and migration tooling.

In other words, the surface area is deliberately wide. If you're building a
tool that has to read COBOL, parse JCL, walk a CICS CSD, understand BMS
maps, or follow data through VSAM / DB2 / IMS / MQ, this repo gives you one
codebase that touches all of those things at once.

Concretely, that "wide surface area" includes:

- **COBOL features**: `REDEFINES`, `OCCURS`, `COMP`, `COMP-3`, and Zoned
  Decimal numerics — the formats most likely to trip up a transpiler.
- **VSAM patterns**: KSDS clusters with Alternate Indexes (AIX), plus ESDS,
  RRDS, and GDG examples.
- **Record formats**: VB and FBA in addition to the usual FB.
- **Three optional extension modules** (see `architecture.md`) layering
  DB2, IMS, and MQ on top of the VSAM core, so a tool can be tested
  against each access pattern in isolation or together.

## Who it's for

This repo is aimed at:

- Developers doing **mainframe discovery or modernization assessment** who
  need a non-confidential codebase to demo against.
- Authors of **migration tooling** (static analyzers, COBOL-to-Java/.NET
  transpilers, JCL converters, schema extractors) who need a known-good
  fixture.
- Engineers learning the shape of a real mainframe application — file
  layout, naming conventions, the online-plus-batch split — without having
  to find a real one.

It is **not** aimed at end-users of a credit card system, and the data
files under `app/data/` are synthetic.

## What it is not

- **Not runnable on a laptop without an emulator.** The source targets
  z/OS. Pre-packaged runtimes for Micro Focus and UniKix live in
  `samples/m2/mf/` and `samples/m2/unikix/` respectively if you want
  off-mainframe execution.
- **Not AWS-Mainframe-Modernization-only.** The `m2` directory hint
  refers to AWS M2, but the source is portable mainframe code that will
  run on any compatible z/OS environment.
- **Not a complete migration framework.** The `scripts/` directory ships
  shell helpers for pushing source and submitting jobs over an FTP
  tunnel, but conversion / lift-and-shift logic is out of scope.

## Tech stack at a glance

**Core (always present):**

| Layer       | Technology                                        |
| ----------- | ------------------------------------------------- |
| Language    | COBOL (with some HLASM)                           |
| Online      | CICS + BMS maps                                   |
| Batch       | JCL                                               |
| Data        | VSAM (KSDS with Alternate Indexes), PS datasets   |
| Security    | RACF                                              |
| Scheduling  | CA7 and Control-M definitions (`app/scheduler/`)  |

**Optional extension modules:**

| Module                              | Adds                                      |
| ----------------------------------- | ----------------------------------------- |
| `app-transaction-type-db2/`         | DB2 for transaction-type reference data   |
| `app-vsam-mq/`                      | IBM MQ for async account / date inquiries |
| `app-authorization-ims-db2-mq/`     | IMS DB + DB2 + MQ for authorizations      |

## Login & entry point

When the application is up on a mainframe, users sign on through CICS:

- **Transaction**: `CC00`
- **Admin**: `ADMIN001` / `PASSWORD`
- **User**: `USER0001` / `PASSWORD`

(Lifted from the root README so this doc stands alone — verify against the
root README before relying on it for an actual deployment.)
