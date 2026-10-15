#!/bin/sh
# Activate this PDI installation and check its license, entirely offline. Run without arguments for help:
#   ./license.sh request <license key>      print the request code for this machine
#   ./license.sh install <activation code>  install the activation code issued for it
#   ./license.sh status | deactivate

BASEDIR="`dirname $0`"
cd "$BASEDIR"
DIR="`pwd`"
cd - > /dev/null

. "$DIR/set-pentaho-env.sh"
setPentahoEnv

LICENSE_JAR="`ls "$DIR"/lib/kettle-license-*.jar 2> /dev/null | head -1`"
if [ -z "$LICENSE_JAR" ]; then
	echo "kettle-license jar not found in $DIR/lib"
	exit 2
fi
"$_PENTAHO_JAVA" -cp "$LICENSE_JAR" org.pentaho.di.license.cli.LicenseCli "$@"
