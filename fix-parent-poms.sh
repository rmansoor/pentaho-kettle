#!/bin/bash

# Script to fix parent POM references in restructured step plugins

PLUGINS_DIR="/Users/ramaizmansoor/Developer/pentaho-kettle/plugins"

# List of step plugins to fix
STEP_PLUGINS=(
    "constant-step"
    "databasejoin-step"
    "databaselookup-step"
    "datagrid-step"
    "detectlastrow-step"
    "detectemptystream-step"
    "fieldsplitter-step"
    "filterrows-step"
    "getfilenames-step"
    "groupby-step"
    "jobexecutor-step"
    "mapping-step"
    "selectvalues-step"
    "sort-step"
    "tableinput-step"
    "tableoutput-step"
)

echo "Fixing parent POM references for step plugins..."

for plugin in "${STEP_PLUGINS[@]}"; do
    POM_FILE="${PLUGINS_DIR}/${plugin}/pom.xml"
    
    if [ -f "$POM_FILE" ]; then
        echo "Fixing $plugin/pom.xml"
        
        # Fix parent artifactId from kettle-plugins to pdi-plugins
        sed -i '' 's/<artifactId>kettle-plugins<\/artifactId>/<artifactId>pdi-plugins<\/artifactId>/g' "$POM_FILE"
        
        # Fix version from 10.2.0.0-SNAPSHOT to 11.0.0.0-SNAPSHOT
        sed -i '' 's/<version>10\.2\.0\.0-SNAPSHOT<\/version>/<version>11.0.0.0-SNAPSHOT<\/version>/g' "$POM_FILE"
        
        echo "Fixed $plugin"
    else
        echo "Warning: $POM_FILE not found"
    fi
done

echo "Completed fixing parent POM references."
