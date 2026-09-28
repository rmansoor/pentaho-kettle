#!/usr/bin/env bash
# Builds the two packages pentaho-kettle 9.4 takes from sibling checkouts instead of Artifactory, and installs
# them into the local Maven repository. Run it before building kettle (qa/cve-remediation/run_all.sh --build).
#
#   qa/build_local_deps.sh [--tests]
#
#   CASSANDRA_DIR  pentaho-cassandra-plugin checkout, branch 9.4-jdk21-cve   (default ~/Developer/pentaho-cassandra-plugin)
#   REPORTING_DIR  pentaho-reporting checkout or worktree, branch 9.4-jdk21-poi5 (default ~/Developer/pentaho-reporting-9.4)
#
# Both carry their own release version (the *_VERSION below), which kettle pins (assemblies/plugins/pom.xml
# pentaho-cassandra-plugin.version, root pom classic-core.version). A release version is never replaced by the
# remote 9.4.0.0-SNAPSHOT builds, so kettle always packages exactly these. When one of them changes, bump its
# version in its repo, here, in the kettle pom that pins it and in qa/cve-remediation/verify_dist.py.
set -euo pipefail

CASSANDRA_VERSION=9.4.0.0-jdk21-2     # pentaho-kettle assemblies/plugins/pom.xml pentaho-cassandra-plugin.version
CLASSIC_CORE_VERSION=9.4.0.0-jdk21-1  # pentaho-kettle pom.xml classic-core.version
CASSANDRA_DIR="${CASSANDRA_DIR:-$HOME/Developer/pentaho-cassandra-plugin}"
REPORTING_DIR="${REPORTING_DIR:-$HOME/Developer/pentaho-reporting-9.4}"
TESTS="-DskipTests"
[[ "${1:-}" == "--tests" ]] && TESTS=""

is21() { [[ -n "${1:-}" ]] && "$1/bin/java" -version 2>&1 | grep -q 'version "21'; }
if ! is21 "${JAVA_HOME:-}"; then
  JAVA_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
fi
is21 "$JAVA_HOME" || { echo "needs JDK 21: set JAVA_HOME to a Java 21 JDK"; exit 1; }
export JAVA_HOME

check() {  # check <dir> <pom> <expected version>
  [[ -d "$1" ]] || { echo "missing checkout: $1"; exit 1; }
  grep -q "<version>$3</version>" "$1/$2" || { echo "$1/$2 is not at $3 (wrong branch?)"; exit 1; }
  echo "== $(basename "$1") ($(git -C "$1" rev-parse --abbrev-ref HEAD) @ $(git -C "$1" rev-parse --short HEAD))"
}

# Cassandra plugin: cassandra-all 3.11.19, driver 3.11.5, netty 4.1; guava/snakeyaml/jackson from kettle lib/
check "$CASSANDRA_DIR" pom.xml "$CASSANDRA_VERSION"
(cd "$CASSANDRA_DIR" && mvn -q clean install $TESTS)

# Reporting engine core (classic-core) against POI 5.5.1. Its parents carry the POI 5 versions, so they are
# installed first; the rest of the reporting jars keep coming from Artifactory.
check "$REPORTING_DIR" engine/core/pom.xml "$CLASSIC_CORE_VERSION"
(cd "$REPORTING_DIR" && mvn -q -N install && mvn -q -N install -f engine/pom.xml)
if [[ -z "$TESTS" ]]; then
  (cd "$REPORTING_DIR" && mvn -q clean install -pl engine/core -Dtest='*Excel*,*Xls*,*XSSF*,*HSSF*' \
    -Dsurefire.failIfNoSpecifiedTests=false \
    "-Dmaven-surefire-plugin.argLine=--add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/java.lang.reflect=ALL-UNNAMED --add-opens=java.desktop/java.awt=ALL-UNNAMED --add-opens=java.desktop/java.awt.font=ALL-UNNAMED")
else
  (cd "$REPORTING_DIR" && mvn -q clean install -pl engine/core -DskipTests)
fi

M2="${MAVEN_REPO:-$HOME/.m2/repository}"
for f in "org/pentaho/pentaho-cassandra-plugin-package/$CASSANDRA_VERSION/pentaho-cassandra-plugin-package-$CASSANDRA_VERSION.zip" \
         "org/pentaho/reporting/engine/classic-core/$CLASSIC_CORE_VERSION/classic-core-$CLASSIC_CORE_VERSION.jar"; do
  [[ -f "$M2/$f" ]] || { echo "not installed: $M2/$f"; exit 1; }
  echo "installed $(basename "$f")"
done
