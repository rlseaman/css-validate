# CSS-Local Changes to NASA-PDS validate 3.2.0

This file documents every deviation from the upstream
[NASA-PDS/validate v3.2.0](https://github.com/NASA-PDS/validate/releases/tag/v3.2.0)
source tree.  Each entry records what changed, why, and the evidence supporting
the change.

CSS-local modifications are marked with `// CSS-LOCAL:` comments in Java source.

---

## Build instructions

### Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| JDK  | 11 or 17 | Must set `JAVA_HOME` if system default is < 11 |
| Maven | 3.5+ | RHEL 8: `sudo dnf install maven` |

### One-time setup: install SNAPSHOT dependencies

validate 3.2.0 depends on two NASA-PDS SNAPSHOT artifacts that are no longer
served by the public snapshot repository:

```
gov.nasa.pds:opencsv:5.4-SNAPSHOT
gov.nasa.pds:vicario:48.0.3-SNAPSHOT
```

Install them into the local Maven cache from the pre-built distribution JARs
(adjust the path to wherever validate-3.2.0/ lives):

```bash
DIST=/path/to/validate-3.2.0/lib

mvn install:install-file \
  -Dfile=$DIST/opencsv-5.4-20210616.043831-1.jar \
  -DgroupId=gov.nasa.pds -DartifactId=opencsv \
  -Dversion=5.4-SNAPSHOT -Dpackaging=jar

mvn install:install-file \
  -Dfile=$DIST/vicario-48.0.3-20210616.051212-1.jar \
  -DgroupId=gov.nasa.pds -DartifactId=vicario \
  -Dversion=48.0.3-SNAPSHOT -Dpackaging=jar

# Clear any negative-cache entries left by earlier failed lookups
find ~/.m2/repository/gov/nasa/pds/opencsv -name "*.lastUpdated" -delete
find ~/.m2/repository/gov/nasa/pds/vicario -name "*.lastUpdated" -delete
```

This only needs to be done once per developer machine.

### Building

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-<version>   # or java-11-openjdk-*
mvn clean package -DskipTests -nsu     # fast build, skip tests
mvn clean package -nsu                  # full build with tests
```

The `-nsu` flag (`--no-snapshot-updates`) prevents Maven from contacting the
remote snapshot repository for the two NASA-PDS artifacts above, ensuring the
locally installed versions are used.

Output: `target/validate-3.2.0.jar`

### Deploying the built JAR into an existing distribution

The CSS-built JAR's `MANIFEST.MF` Class-Path references the two SNAPSHOT dependencies
by their canonical Maven SNAPSHOT names (`opencsv-5.4-SNAPSHOT.jar`,
`vicario-48.0.3-SNAPSHOT.jar`).  The pre-built distribution ships them with timestamped
names (`opencsv-5.4-20210616.043831-1.jar`, `vicario-48.0.3-20210616.051212-1.jar`).

After copying `target/validate-3.2.0.jar` into `<dist>/lib/`, create two symlinks:

```bash
cd <dist>/lib
ln -sf opencsv-5.4-20210616.043831-1.jar  opencsv-5.4-SNAPSHOT.jar
ln -sf vicario-48.0.3-20210616.051212-1.jar vicario-48.0.3-SNAPSHOT.jar
```

Without these symlinks, `DataDefinitionAndContentValidationRule` fails with
`ClassNotFoundException: com.opencsv.exceptions.CsvValidationException` for all
table-type products.  Image products (which hit the fast path before class loading)
are unaffected, making the failure appear only at ~34% of products — the table types.

### Running the freshly built validate

The distribution launcher script (`validate-3.2.0/bin/validate`) references the
distribution JAR.  To run the freshly built JAR directly:

```bash
java -Xms2048m -Xmx4096m \
     -Dresources.home=src/main/resources \
     -Dcom.sun.xml.bind.v2.bytecode.ClassTailor.noOptimize=true \
     -jar target/validate-3.2.0.jar "$@"
```

---

## CSS-local modifications

---

### AppCDS support (Phase 1)

**Files changed:**
- `src/main/resources/bin/validate` — launcher conditionally enables AppCDS
- `rebuild_appcds.sh` — new script to regenerate the class-data archive
- `.gitignore` — add `validate.jsa` and `validate.classlist` (machine-specific)

**What it does:**

AppCDS (Application Class Data Sharing) serialises the JVM's completed class-loading
work — parsed bytecode, verified metadata, resolved references — into a binary archive
(`.jsa`).  On subsequent `validate` invocations the JVM memory-maps the archive instead
of re-parsing all JAR files.  The OS can share those physical pages across parallel JVM
processes.

The launcher checks for `${PARENT_DIR}/validate.jsa` at startup:

```sh
APPCDS_ARCHIVE="${PARENT_DIR}/validate.jsa"
if [ -f "${APPCDS_ARCHIVE}" ]; then
    APPCDS_FLAGS="-XX:SharedArchiveFile=${APPCDS_ARCHIVE} -Xshare:auto"
else
    APPCDS_FLAGS=""
fi
```

`-Xshare:auto` silently falls back to normal startup if the archive is absent or stale,
so a missed rebuild never breaks production.

**Generating the archive:**

```bash
bash rebuild_appcds.sh [distribution_dir] [catalog_path]
```

Run this once after installing validate, and again after any Java update or new
validate deployment.  Takes < 2 minutes.

**Expected gain (CSS workload):**

| Scenario | AppCDS saving | Total rate | % gain |
|----------|--------------|-----------|--------|
| 100-prod batch, content validation | ~20 ms/product | ~1,500 ms/prod | ~1–2% |
| 25-prod batch (4 parallel), content | ~80 ms/product | ~500 ms/prod | ~5% |
| 100-prod batch, label-only (-D) | ~20 ms/product | ~210 ms/prod | ~10% |

**Known issue — RHEL 8 / Red Hat OpenJDK 17.0.5:**

`rebuild_appcds.sh` triggers a JVM crash (`SIGSEGV` in
`SystemDictionaryShared::adjust_lambda_proxy_class_dictionary()`) specific to Red Hat's
OpenJDK 17.0.5 build on RHEL 8.  Additional constraints on this platform:

- Saxon HE (XSLT engine used for Schematron) is a signed JAR; its classes are skipped
  during archive creation regardless of approach.
- validate calls `System.exit()` which bypasses JVM shutdown hooks, making the
  `-XX:ArchiveClassesAtExit` approach ineffective.
- The default Java 17 CDS archive (`$JAVA_HOME/lib/server/classes.jsa`, 14 MB) already
  provides JDK-level class sharing and loads automatically.

The AppCDS infrastructure is correct and works on standard JDK builds.  The archive
build is deferred until either a non-Red Hat JDK build is available on this host or the
JVM bug is resolved upstream.

---

### ArrayContentValidator fast path (Phase 2)

**File:** `src/main/java/gov/nasa/pds/tools/validate/content/array/ArrayContentValidator.java`

**Benchmark results** (RHEL 8, Java 17, G96 5280×5280 SignedMSB2 arch images,
pre-funpacked, 2 trials each, all products PASS 0 errors):

| Batch size | Original ms/product | CSS ms/product | Speedup |
|------------|---------------------|----------------|---------|
| 10 products  | 1,998 ms/prod | 602 ms/prod  | **3.3×** |
| 30 products  | 1,693 ms/prod | 324 ms/prod  | **5.2×** |
| 100 products | 1,517 ms/prod | 202 ms/prod  | **7.5×** |

Per-product content-validation speedup (startup amortised out): ~10×.

**Why the original loop was redundant for signed integers:**

For CSS FITS images (`SignedMSB2`, no `Special_Constants`, no `Object_Statistics`),
the existing per-pixel loop called `rangeChecker.contains(value)` 27,878,400 times per
image.  The check was tautological: the `(short)` cast at line 200 of `validatePosition()`
already constrains the value to `[Short.MIN_VALUE, Short.MAX_VALUE]`, which is exactly
the range `SignedMSB2_RANGE` spans.  The same argument applies to all 7 signed integer
types — each Java primitive cast is the only narrowing that can occur.

The only real work the loop performed for these types was confirming the data file
contained enough bytes for all N pixels without premature EOF.

**What the fast path does:**

Inserted at the top of `validate()`, before `arrayObject.open()`:

1. Determine the data type from the label.  If not a signed integer, fall through.
2. Check `array.getSpecialConstants() == null` and `array.getObjectStatistics() == null`.
   If either is non-null, fall through.
3. Compute `expectedBytes = totalElements × (bits/8)`.
4. `Files.size(Paths.get(dataFile.toURI()))` — one `stat()` syscall.
5. If `fileSize < offset + expectedBytes`, report `ARRAY_DATA_FILE_READ_ERROR`.
6. Otherwise return immediately — file is large enough and all pixels are in range.

Any exception (URI error, I/O error, unknown type) causes a fall-through to the
original pixel-by-pixel loop, which reports the error normally.

**Fast path conditions (all must be true):**
1. Data type is one of the 7 signed integer types (`SignedByte` … `SignedMSB8`)
2. `array.getSpecialConstants()` is `null`
3. `array.getObjectStatistics()` is `null`

All other array types (unsigned, float) fall through to the original loop unchanged.
New imports: `java.nio.file.Files`, `java.nio.file.Paths` (Java 7+, compatible with
the Java 11 minimum requirement).

**Correctness argument:**

The fast path reports identical errors to the original loop because:
- For signed integer types with no Special_Constants, `rangeChecker.contains(value)`
  is always `true` → the original loop never reports a range error for these types.
- The original loop's only detectable failure mode is a read error (premature EOF),
  which is exactly what the file-size check detects.
- `Object_Statistics` min/max checks are bypassed when that field is null (as it is
  for all CSS data), so no `checkObjectStats()` calls are skipped.

The change is conservative: any deviation from these conditions uses the original loop.

---

### Planned additions

- **CSS test articles** — `src/test/resources/css-testdata/`
- **CSS unit + integration tests** — `src/test/java/`
