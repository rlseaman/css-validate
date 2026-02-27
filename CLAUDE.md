# css-validate — Claude Project Context

## What this repository is

CSS-maintained fork of [NASA-PDS/validate v3.2.0](https://github.com/NASA-PDS/validate),
the PDS4 data validation engine used by PSI and all other PDS nodes.

**Upstream:** `https://github.com/NASA-PDS/validate` (tag v3.2.0, remote: `upstream`)
**CSS fork:** `https://github.com/rlseaman/css-validate` (remote: `origin`)
**Local path:** `~/Claude/css-validate/`
**Language:** Java 11+ / Maven

## Scope of changes in this fork

CSS adds a performance fast path and a test suite.  All CSS changes are marked
with `// CSS-LOCAL:` comments and catalogued in `CHANGES.md`.

**In scope:**
- Java content validation fast path for signed integer arrays
- AppCDS launcher configuration (JVM startup cache)
- CSS-specific test articles and integration tests
- Build documentation

**Not in scope:**
- Pipeline orchestration → `rlseaman/psi-catalina`
- CSS benchmark/operational scripts → `rlseaman/CSS_PDS4_tools`

## The fast path (primary CSS change — not yet implemented)

**File:** `src/main/java/gov/nasa/pds/tools/validate/content/array/ArrayContentValidator.java`

For CSS FITS images (`SignedMSB2`, no `Special_Constants`, no `Object_Statistics`),
the current per-pixel loop calls `rangeChecker.contains(value)` 27,878,400 times
per image.  The check is a tautology: a Java `short` cast at line 200 already
constrains the value to `[Short.MIN_VALUE, Short.MAX_VALUE]`, which is exactly
the range being checked.

The planned replacement: a single `FileChannel.read()` into a `ByteBuffer` of
the expected byte count, verifying file size and readability without iterating
pixels.  Expected speedup: ~6× per FITS image; ~24× total vs vanilla PSI.

The fast path activates only when:
1. Data type is one of the 7 signed integer types (SignedByte through SignedMSB8)
2. `array.getSpecialConstants()` is null
3. `array.getObjectStatistics()` is null

All other data (unsigned, float, data with special constants) uses the original
unmodified loop.  Java 11 compatible — uses only `java.nio` APIs available since
Java 1.4.

## Building

**Prerequisite (one-time):** install SNAPSHOT JARs into local Maven cache.
See `CHANGES.md` for the exact commands.

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-<version>   # or java-11
mvn clean package -DskipTests -nsu    # fast build
mvn clean package -nsu                 # full build with tests
```

The `-nsu` flag prevents Maven from re-checking remote repos for the two
NASA-PDS SNAPSHOT dependencies that are no longer publicly available.

Output: `target/validate-3.2.0.jar`

## Key files

| File | Purpose |
|------|---------|
| `src/main/java/.../content/array/ArrayContentValidator.java` | Primary target for fast path |
| `src/main/java/.../rule/pds4/LabelInFolderRule.java` | Thread pool hardcoded to 1 (upstream bug #167/#180) — do not change |
| `src/main/resources/bin/validate` | Launcher script — target for AppCDS flags |
| `CHANGES.md` | Full record of every CSS-local modification |

## CSS FITS data profile

- **Format:** FITS tile-compressed (`.fz`), Rice algorithm; funpacked before validation
- **Decompressed:** 5280×5280 pixels, `BITPIX=16` (signed 16-bit big-endian)
- **PDS4 type:** `SignedMSB2`
- **Physical pixels:** unsigned 0–65535 via FITS `BZERO=32768` convention (FITS §5.2.5)
  — PDS4 validate checks raw stored bytes only, not physical interpretation
- **Size:** ~55.7 MB decompressed, ~30 MB compressed
- **Special_Constants:** not used in CSS labels
- **Object_Statistics:** not used in CSS labels
→ Fast path condition is unconditionally satisfied for all CSS FITS images.

## Java version compatibility

All CSS changes must work on **Java 11** (PSI's production version,
Ubuntu 22.04 / OpenJDK 11.0.30).  Do not use Java 17+ language features.
The fast path uses only `java.nio.ByteBuffer` and `java.nio.channels.FileChannel`,
available since Java 1.4.

## Related repositories

| Repo | What it is |
|------|-----------|
| `rlseaman/psi-catalina` | Fork of sbn-psi/catalina with parallel batches |
| `rlseaman/CSS_PDS4_tools` | CSS label generation + operational benchmark tools |
| `NASA-PDS/validate` | Upstream validate engine |
| `sbn-psi/catalina` | PSI pipeline that calls this tool |

## Working notes

Detailed analysis, benchmark results, and strategic plan:
- `~/Claude/SBN-PSI/NOTES.md` — canonical session record and benchmark data
- `~/Claude/SBN-PSI/css-validate-plan-2026-02-27.md` — strategic plan
- `~/Claude/SBN-PSI/analysis-report-2026-02-25.md` — fast path mathematical argument
- `~/Claude/SBN-PSI/appcds-notes-2026-02-26.md` — AppCDS setup procedure
