#!/usr/bin/env bash
# Builds the packages pentaho-kettle 9.4 takes from sibling checkouts instead of Artifactory, and installs
# them into the local Maven repository. Run it before building kettle (qa/cve-remediation/run_all.sh --build).
#
#   qa/build_local_deps.sh [--tests]
#
#   CASSANDRA_DIR  pentaho-cassandra-plugin checkout, branch 9.4-jdk21-cve   (default ~/Developer/pentaho-cassandra-plugin)
#   REPORTING_DIR  pentaho-reporting checkout or worktree, branch 9.4-jdk21-poi5 (default ~/Developer/pentaho-reporting-9.4)
#   PLATFORM_DIR   pentaho-platform checkout or worktree, branch 9.4-jdk21-spring7 (default ~/Developer/pentaho-platform-9.4)
#   LANG2_DIR      commons-lang 2.6 source, branch 2.6-pentaho                (default ~/Developer/commons-lang-2.6-pentaho)
#   METAVERSE_DIR  pentaho-metaverse checkout or worktree, branch 9.4-jdk21-lineage (default ~/Developer/pentaho-metaverse-9.4)
#
# Each carries their own release version (the *_VERSION below), which kettle pins (assemblies/plugins/pom.xml
# pentaho-cassandra-plugin.version, root pom classic-core.version, platform-spring7.version, commons-lang.version, metaverse-api.version). A release version is never replaced by the
# remote 9.4.0.0-SNAPSHOT builds, so kettle always packages exactly these. When one of them changes, bump its
# version in its repo, here, in the kettle pom that pins it and in qa/cve-remediation/verify_dist.py.
set -euo pipefail

CASSANDRA_VERSION=9.4.0.0-jdk21-3     # pentaho-kettle assemblies/plugins/pom.xml pentaho-cassandra-plugin.version
CLASSIC_CORE_VERSION=9.4.0.0-jdk21-2  # pentaho-kettle pom.xml classic-core.version
PLATFORM_VERSION=9.4.0.0-jdk21-2      # pentaho-kettle pom.xml platform-spring7.version (api, core, repository)
LANG2_VERSION=2.6.0.1-pentaho-jdk21   # pentaho-kettle pom.xml commons-lang.version
METAVERSE_VERSION=9.4.0.0-jdk21-2     # pentaho-kettle pom.xml metaverse-api.version (pentaho-metaverse-api only)
CASSANDRA_DIR="${CASSANDRA_DIR:-$HOME/Developer/pentaho-cassandra-plugin}"
REPORTING_DIR="${REPORTING_DIR:-$HOME/Developer/pentaho-reporting-9.4}"
PLATFORM_DIR="${PLATFORM_DIR:-$HOME/Developer/pentaho-platform-9.4}"
LANG2_DIR="${LANG2_DIR:-$HOME/Developer/commons-lang-2.6-pentaho}"
METAVERSE_DIR="${METAVERSE_DIR:-$HOME/Developer/pentaho-metaverse-9.4}"
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

# Pentaho platform api, core and repository on Spring 7 / Spring Security 7 (the platform jars PDI runs).
# Mockito's ByteBuddy needs the experimental flag to read Java 21 class files in their tests.
check "$PLATFORM_DIR" core/pom.xml "$PLATFORM_VERSION"
(cd "$PLATFORM_DIR" && mvn -q -N install && mvn -q clean install -pl api,core,repository $TESTS \
  "-Dmaven-surefire-plugin.argLine=--add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/java.lang.reflect=ALL-UNNAMED --add-opens=java.base/java.io=ALL-UNNAMED -Dnet.bytebuddy.experimental=true")

# commons-lang 2.6 with the ClassUtils.getClass fix (CVE-2025-48924); same classes and API as Apache's 2.6.
check "$LANG2_DIR" pom.xml "$LANG2_VERSION"
(cd "$LANG2_DIR" && mvn -q clean install $TESTS)

# pentaho-metaverse-api: lineage opens its TinkerGraph without commons-configuration (CVE-2025-46392). Only the
# api jar is rebuilt; the metaverse-plugin zip still comes from Artifactory.
check "$METAVERSE_DIR" api/pom.xml "$METAVERSE_VERSION"
(cd "$METAVERSE_DIR" && mvn -q -N install && mvn -q clean install -pl api $TESTS)

M2="${MAVEN_REPO:-$HOME/.m2/repository}"
for f in "org/pentaho/pentaho-cassandra-plugin-package/$CASSANDRA_VERSION/pentaho-cassandra-plugin-package-$CASSANDRA_VERSION.zip" \
         "org/pentaho/reporting/engine/classic-core/$CLASSIC_CORE_VERSION/classic-core-$CLASSIC_CORE_VERSION.jar" \
         "pentaho/pentaho-platform-api/$PLATFORM_VERSION/pentaho-platform-api-$PLATFORM_VERSION.jar" \
         "pentaho/pentaho-platform-core/$PLATFORM_VERSION/pentaho-platform-core-$PLATFORM_VERSION.jar" \
         "pentaho/pentaho-platform-repository/$PLATFORM_VERSION/pentaho-platform-repository-$PLATFORM_VERSION.jar" \
         "commons-lang/commons-lang/$LANG2_VERSION/commons-lang-$LANG2_VERSION.jar" \
         "pentaho/pentaho-metaverse-api/$METAVERSE_VERSION/pentaho-metaverse-api-$METAVERSE_VERSION.jar"; do
  [[ -f "$M2/$f" ]] || { echo "not installed: $M2/$f"; exit 1; }
  echo "installed $(basename "$f")"
done
