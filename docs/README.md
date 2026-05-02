# Developer Documentation

This folder is the **developer's on-ramp** to the repository. The root `README.md`
covers installation and operations; the docs here cover what the project _is_,
how it's _organized_, and where to find things.

Read in this order:

1. **[overview.md](./overview.md)** — What CardDemo is, why this repo exists,
   who it's for, and the tech stack at a glance. Start here.
2. **[architecture.md](./architecture.md)** — How the application is put
   together: online vs. batch execution paths, the core app plus three optional
   extension modules, the data flow, and the build-and-deploy mechanics
   provided by the `scripts/` shell wrappers.
3. **[directory-guide.md](./directory-guide.md)** — One paragraph per
   directory in the repo, with file types and representative filenames. Use as
   a reference when navigating the codebase.
4. **[cobol/](./cobol/README.md)** — Per-program reference for all 44 COBOL
   programs (online, batch, and the three extension modules). Lookup-style:
   what each program does, what it reads/writes, and the CICS/SQL/MQ/IMS
   API calls it makes.

For installation, dataset definitions, batch job sequencing, and the catalog
of online transactions, see the root [`README.md`](../README.md). It is the
source of truth for operating the application on a mainframe.
