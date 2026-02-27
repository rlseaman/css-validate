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

*None yet.  This section will be updated as changes are made.*

Planned changes (see `css-validate-plan-2026-02-27.md` in the SBN-PSI notes):

- **AppCDS launcher flags** — `src/main/resources/bin/validate` (config only,
  no logic change)
- **ArrayContentValidator fast path** —
  `src/main/java/gov/nasa/pds/tools/validate/content/array/ArrayContentValidator.java`
  (bulk-read fast path for signed integer arrays with no Special_Constants or
  Object_Statistics; ~6× speedup for CSS FITS images)
- **CSS test articles** — `src/test/resources/css-testdata/` (new directory)
- **CSS unit + integration tests** — `src/test/java/` (new test classes)
