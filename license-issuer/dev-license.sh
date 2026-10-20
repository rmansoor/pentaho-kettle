#!/usr/bin/env bash
# For testing only: plays Hitachi Vantara with the DEVELOPMENT signing key (accepted only while PDI embeds it).
#   ./dev-license.sh key [name] [term]    print a new license key, as a customer would receive it (term: 1y, 2y,
#                                         ..., perpetual; default 1y)
#   ./dev-license.sh renew <key> [term]   renew a license key: prints the new key and an activation code per machine
#   ./dev-license.sh answer <request>     print the activation code for an activation request (the code, or the
#                                         file holding it, e.g. activation-request.txt)
# Ledger: ~/.pdi-license-dev/ledger.tsv. Build first: mvn -pl license,license-issuer install
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
KEY="$HERE/../license/src/test/resources/org/pentaho/di/license/dev-private-key.der"
CP="$HERE/target/classes:$HERE/../license/target/classes"
[[ -d "$HERE/target/classes" ]] || { echo "build first: mvn -pl license,license-issuer install" >&2; exit 2; }
if [[ -n "${JAVA_HOME:-}" ]]; then JAVA="$JAVA_HOME/bin/java"
elif [[ -x /usr/libexec/java_home ]]; then JAVA="$(/usr/libexec/java_home -v 17+)/bin/java"
else JAVA=java; fi
LEDGER="$HOME/.pdi-license-dev/ledger.tsv"
issuer() { "$JAVA" -cp "$CP" org.pentaho.di.license.issuer.LicenseIssuer "$@"; }
case "${1:-}" in
  key)    issuer issue --private-key "$KEY" --ledger "$LEDGER" --customer "${2:-Test customer}" --term "${3:-1y}" ;;
  renew)  [[ $# -ge 2 ]] || { echo "usage: $0 renew <license key | file> [term]" >&2; exit 2; }
          issuer renew --private-key "$KEY" --ledger "$LEDGER" --term "${3:-1y}" "$2" ;;
  answer) [[ $# -eq 2 ]] || { echo "usage: $0 answer <request code | file>" >&2; exit 2; }
          issuer activate --private-key "$KEY" --ledger "$LEDGER" "$2" ;;
  *)      sed -n '2,8p' "$0" | sed 's/^# \{0,1\}//'; exit 2 ;;
esac
