#!/usr/bin/env bash
# Builds PDI 10.1 with the CVE remediation from scratch and unpacks it, ready to run and scan.
#
#   qa/build_10.1.sh [--fresh-m2] [--test]
#
#   1. clones pentaho-kettle and the 12 sibling repos, branch 10.1-jdk21-cve, into $SRC
#   2. builds the siblings (qa/build_local_deps.sh), kettle, the kettle-dependent siblings again
#      against that kettle (--post), then kettle's assemblies
#   3. unpacks pdi-ce-10.1.0.0-SNAPSHOT.zip into $OUT (default ~/pdi-10.1/data-integration)
#   4. with --test: runs qa/cve-remediation/run_all.sh (distribution checks, grype + Trivy, smoke; needs Docker)
#
#   --fresh-m2   use an empty Maven repository ($SRC/.m2) instead of ~/.m2, so nothing built earlier on this
#                machine can leak into the result (slower: everything is downloaded again)
#
#   SRC     where the repos are cloned (default ~/pdi-10.1-src; an existing clone is updated, not re-cloned)
#   OUT     where PDI is unpacked (default ~/pdi-10.1)
#   GITHUB  owner of the forks (default rmansoor)
#   REGISTRY_URL, MONGODB_URL: pentaho-registry and pentaho-mongodb-plugin have no fork on GitHub yet; they are
#           cloned from the local checkouts ~/Developer/<repo>-10.1 unless these are set
#
# Needs JDK 21, Maven 3.9, git, and access to Pentaho's Artifactory through ~/.m2/settings.xml.
set -euo pipefail

BRANCH=10.1-jdk21-cve
SRC="${SRC:-$HOME/pdi-10.1-src}"
OUT="${OUT:-$HOME/pdi-10.1}"
GITHUB="${GITHUB:-rmansoor}"
REGISTRY_URL="${REGISTRY_URL:-$HOME/Developer/pentaho-registry-10.1}"
MONGODB_URL="${MONGODB_URL:-$HOME/Developer/pentaho-mongodb-plugin-10.1}"
FRESH=0; TEST=0
for a in "$@"; do
  case "$a" in
    --fresh-m2) FRESH=1 ;;
    --test) TEST=1 ;;
    *) echo "unknown option $a"; exit 2 ;;
  esac
done

export JAVA_HOME="${JAVA_HOME_21:-$(/usr/libexec/java_home -v 21)}"
ulimit -n 65536 2>/dev/null || true   # the kettle build opens more files than macOS allows by default
if [[ "$FRESH" == 1 ]]; then
  export MAVEN_REPO="$SRC/.m2"
  export MAVEN_ARGS="${MAVEN_ARGS:-} -Dmaven.repo.local=$MAVEN_REPO"
fi
mkdir -p "$SRC"
LOG="$SRC/logs"; mkdir -p "$LOG"

step() { echo; echo "== $(date +%H:%M:%S) $*"; }
run() {  # run <log name> <command...>: output to $LOG/<name>.log, tail it on failure
  if ! "${@:2}" >"$LOG/$1.log" 2>&1; then
    tail -40 "$LOG/$1.log"; echo "FAILED: $1 (full log $LOG/$1.log)"; exit 1
  fi
}

# ------------------------------------------------------------------------------------------------ 1. clone
step "1. clone $BRANCH into $SRC"
clone() {  # clone <dir name> <url>
  local dir="$SRC/$1"
  if [[ -d "$dir/.git" ]]; then
    git -C "$dir" fetch -q --depth 1 origin "$BRANCH" && git -C "$dir" checkout -q -B "$BRANCH" FETCH_HEAD
  else
    git clone -q --depth 1 --branch "$BRANCH" "$2" "$dir"
  fi
  echo "   $1 @ $(git -C "$dir" rev-parse --short HEAD)"
}
for r in pentaho-kettle pentaho-platform pentaho-reporting pentaho-cassandra-plugin pentaho-metaverse \
         pentaho-commons-xul pentaho-metadata mondrian pdi-teradata-tpt-plugin pentaho-vertica-bulkloader \
         big-data-plugin; do
  clone "$r" "https://github.com/$GITHUB/$r.git"
done
clone pentaho-registry "$REGISTRY_URL"
clone pentaho-mongodb-plugin "$MONGODB_URL"

export CASSANDRA_DIR="$SRC/pentaho-cassandra-plugin" REPORTING_DIR="$SRC/pentaho-reporting" \
  PLATFORM_DIR="$SRC/pentaho-platform" METAVERSE_DIR="$SRC/pentaho-metaverse" XUL_DIR="$SRC/pentaho-commons-xul" \
  TPT_DIR="$SRC/pdi-teradata-tpt-plugin" VERTICA_DIR="$SRC/pentaho-vertica-bulkloader" \
  METADATA_DIR="$SRC/pentaho-metadata" REGISTRY_DIR="$SRC/pentaho-registry" MONDRIAN_DIR="$SRC/mondrian" \
  MONGODB_DIR="$SRC/pentaho-mongodb-plugin" BIGDATA_DIR="$SRC/big-data-plugin"
KETTLE="$SRC/pentaho-kettle"

# ------------------------------------------------------------------------------------------------ 2. build
step "2a. sibling repos (logs in $LOG)"
run deps "$KETTLE/qa/build_local_deps.sh"
step "2b. kettle (core, engine, ui, plugins)"
run kettle bash -c "cd '$KETTLE' && mvn clean install -DskipTests -DskipDefault -Pbase,plugins"
step "2c. kettle-dependent siblings against this kettle"
run deps-post "$KETTLE/qa/build_local_deps.sh" --post
step "2d. kettle assemblies (the pdi-ce zip)"
run assemblies bash -c "cd '$KETTLE' && mvn clean install -DskipTests -f assemblies/pom.xml"

ZIP="$KETTLE/assemblies/client/target/pdi-ce-10.1.0.0-SNAPSHOT.zip"
[[ -f "$ZIP" ]] || { echo "no zip at $ZIP"; exit 1; }

# ------------------------------------------------------------------------------------------------ 3. unpack
step "3. unpack into $OUT"
rm -rf "$OUT/data-integration"; mkdir -p "$OUT"
unzip -q "$ZIP" -d "$OUT"
echo "   $OUT/data-integration (kettle $(git -C "$KETTLE" rev-parse --short HEAD))"

# ------------------------------------------------------------------------------------------------ 4. test
if [[ "$TEST" == 1 ]]; then
  step "4. run_all.sh (distribution checks, grype + Trivy, smoke)"
  "$KETTLE/qa/cve-remediation/run_all.sh" | tee "$LOG/run_all.log"
fi

cat <<EOF

PDI 10.1 is in $OUT/data-integration (needs Java 21):
  cd $OUT/data-integration && JAVA_HOME=$JAVA_HOME ./spoon.sh
Scan it:
  grype dir:$OUT/data-integration
  trivy fs $OUT/data-integration
EOF
