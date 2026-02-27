#!/bin/bash
# rebuild_appcds.sh — regenerate validate.jsa after any Java or validate update
#
# Run this script from the root of a validate *distribution* directory
# (the one containing bin/, lib/, resources/) — not from the source tree.
#
# Usage:
#   bash /path/to/css-validate/rebuild_appcds.sh [distribution_dir] [catalog_path]
#
# Arguments:
#   distribution_dir  Path to the validate distribution to cache.
#                     Default: the directory containing this script (when run
#                     from a distribution) or ../target/validate-* (from source).
#   catalog_path      Absolute path to schemas/catalog_all.xml.
#                     Default: auto-detected from distribution_dir/../schemas/
#
# The .jsa archive is written to: <distribution_dir>/validate.jsa
# The class list is written to:   <distribution_dir>/validate.classlist
# Both files are in .gitignore — do not commit them.
#
# Rebuild triggers:
#   - Java minor version update (e.g. 17.0.5 -> 17.0.6)
#   - New validate JAR deployed
#   - Migration to a new host
#   - First-time setup on any machine
#
# Expected runtime: < 2 minutes.

set -euo pipefail

# --- Locate distribution directory ---
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# If run from within a built distribution (has lib/validate-*.jar), use this dir.
# Otherwise fall back to the Maven target output.
if [ -n "${1:-}" ]; then
    DIST_DIR="$(cd "$1" && pwd)"
elif ls "${SCRIPT_DIR}/lib/validate-"*.jar >/dev/null 2>&1; then
    DIST_DIR="${SCRIPT_DIR}"
else
    # Running from source tree root — find the Maven-built distribution
    DIST_DIR=$(ls -d "${SCRIPT_DIR}/target/validate-"*/ 2>/dev/null | head -1)
    if [ -z "${DIST_DIR}" ]; then
        echo "ERROR: Could not find a validate distribution. Run 'mvn package -DskipTests -nsu' first," >&2
        echo "       or pass the distribution directory as the first argument." >&2
        exit 1
    fi
    DIST_DIR="${DIST_DIR%/}"
fi

VALIDATE_JAR=$(ls "${DIST_DIR}/lib/validate-"*.jar 2>/dev/null | head -1)
if [ -z "${VALIDATE_JAR}" ]; then
    echo "ERROR: No validate JAR found in ${DIST_DIR}/lib/" >&2
    exit 1
fi

# --- Locate schema catalog ---
if [ -n "${2:-}" ]; then
    CATALOG="$(cd "$(dirname "$2")" && pwd)/$(basename "$2")"
else
    # Auto-detect: look for schemas/ sibling to distribution or source root
    for candidate in \
        "${DIST_DIR}/../schemas/catalog_all.xml" \
        "${SCRIPT_DIR}/src/test/resources/schemas/catalog_all.xml" \
        "${SCRIPT_DIR}/../SBN-PSI/catalina/schemas/catalog_all.xml"
    do
        if [ -f "$candidate" ]; then
            CATALOG="$(cd "$(dirname "$candidate")" && pwd)/$(basename "$candidate")"
            break
        fi
    done
fi

if [ -z "${CATALOG:-}" ] || [ ! -f "${CATALOG}" ]; then
    echo "ERROR: Could not find catalog_all.xml. Pass its path as the second argument." >&2
    exit 1
fi

# --- Paths ---
CLASSLIST="${DIST_DIR}/validate.classlist"
ARCHIVE="${DIST_DIR}/validate.jsa"
RESOURCES="${DIST_DIR}/resources"

JVM_COMMON="-Xms2048m -Xmx4096m \
    -Dresources.home=${RESOURCES} \
    -Dcom.sun.xml.bind.v2.bytecode.ClassTailor.noOptimize=true"

echo "=== rebuild_appcds.sh ==="
echo "  Distribution : ${DIST_DIR}"
echo "  JAR          : $(basename ${VALIDATE_JAR})"
echo "  Catalog      : ${CATALOG}"
echo "  Java         : $(java -version 2>&1 | head -1)"
echo ""

# --- Step A: Collect class list ---
echo "Step A: collecting class list (dry run with 20 labels)..."
TMPDIR_CDS=$(mktemp -d)
trap "rm -rf ${TMPDIR_CDS}" EXIT

LABELDIR=$(dirname "${CATALOG}")/../..
# Find some real arch labels for a representative run; fall back to any .xml files
LABELS=$(find "${LABELDIR}" -name "*.arch.xml" 2>/dev/null | head -20)
if [ -z "${LABELS}" ]; then
    LABELS=$(find "${LABELDIR}" -name "*.xml" 2>/dev/null | head -20)
fi

if [ -z "${LABELS}" ]; then
    echo "  WARNING: No label files found for representative run." >&2
    echo "  Creating minimal stub labels to warm the class list..." >&2
    # A minimal valid-enough invocation to trigger class loading
    touch "${TMPDIR_CDS}/stub"
else
    for xml in ${LABELS}; do
        base=$(basename "${xml}" .xml)
        touch "${TMPDIR_CDS}/${base}"
        cp "${xml}" "${TMPDIR_CDS}/"
    done
fi

java -Xshare:off \
     ${JVM_COMMON} \
     -XX:DumpLoadedClassList="${CLASSLIST}" \
     -jar "${VALIDATE_JAR}" \
     -s json -E 2147483647 -C "${CATALOG}" -t "${TMPDIR_CDS}" \
     >/dev/null 2>&1 || true   # validate may exit non-zero on stub; that's fine

echo "  Class list: $(wc -l < "${CLASSLIST}") classes -> ${CLASSLIST}"

# --- Step B: Build the shared archive ---
# Note: -Xshare:dump must NOT include heap size flags (-Xms/-Xmx).
# It builds a class metadata archive, not a running JVM.
echo "Step B: building shared archive..."
java -Xshare:dump \
     -Dresources.home="${RESOURCES}" \
     -Dcom.sun.xml.bind.v2.bytecode.ClassTailor.noOptimize=true \
     -XX:SharedClassListFile="${CLASSLIST}" \
     -XX:SharedArchiveFile="${ARCHIVE}" \
     -jar "${VALIDATE_JAR}" \
     >/dev/null 2>&1 || {
    echo "ERROR: Archive creation failed. Check Java version compatibility." >&2
    exit 1
}

SIZE=$(du -sh "${ARCHIVE}" 2>/dev/null | cut -f1)
echo "  Archive: ${SIZE} -> ${ARCHIVE}"

# --- Verify ---
echo "Step C: verifying archive is loaded..."
FIRST_LINE=$(java -Xshare:on \
     -XX:SharedArchiveFile="${ARCHIVE}" \
     -Xms2048m -Xmx4096m \
     -Dresources.home="${RESOURCES}" \
     -Dcom.sun.xml.bind.v2.bytecode.ClassTailor.noOptimize=true \
     -verbose:class \
     -jar "${VALIDATE_JAR}" --help 2>&1 | head -1 || true)

if echo "${FIRST_LINE}" | grep -q "shared objects file"; then
    echo "  Verified: classes loading from shared archive."
else
    echo "  WARNING: Could not confirm archive is active. First line of -verbose:class output:"
    echo "  ${FIRST_LINE}"
    echo "  This may be harmless — proceed and benchmark to confirm."
fi

echo ""
echo "Done. The launcher will use the archive automatically (via -Xshare:auto)."
echo "Re-run this script after any Java update or new validate deployment."
