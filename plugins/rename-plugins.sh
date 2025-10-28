#!/bin/bash

# Script to remove -step suffix from plugin directory names and update references

PLUGINS_DIR="/Users/ramaizmansoor/Developer/pentaho-kettle/plugins"
cd "$PLUGINS_DIR"

# List of plugins to rename (remove -step suffix)
STEP_PLUGINS=(
    "calculator-step:calculator"
    "constant-step:constant"
    "csvinput-step:csvinput"
    "databasejoin-step:databasejoin"
    "databaselookup-step:databaselookup" 
    "datagrid-step:datagrid"
    "detectemptystream-step:detectemptystream"
    "detectlastrow-step:detectlastrow"
    "fieldsplitter-step:fieldsplitter"
    "filterrows-step:filterrows"
    "getfilenames-step:getfilenames"
    "groupby-step:groupby"
    "jobexecutor-step:jobexecutor"
    "mapping-step:mapping"
    "selectvalues-step:selectvalues"
    "sort-step:sort"
    "tableinput-step:tableinput"
    "tableoutput-step:tableoutput"
    "textfileinput-step:textfileinput"
    "textfileoutput-step:textfileoutput"
)

echo "Renaming plugin directories and updating POM files..."

for plugin_pair in "${STEP_PLUGINS[@]}"; do
    OLD_NAME=$(echo "$plugin_pair" | cut -d: -f1)
    NEW_NAME=$(echo "$plugin_pair" | cut -d: -f2)
    
    if [ -d "$OLD_NAME" ]; then
        echo "Processing $OLD_NAME -> $NEW_NAME"
        
        # Update the artifactId in the main POM file before renaming
        if [ -f "$OLD_NAME/pom.xml" ]; then
            echo "  Updating POM artifactId: $OLD_NAME -> $NEW_NAME"
            sed -i '' "s/<artifactId>$OLD_NAME<\/artifactId>/<artifactId>$NEW_NAME<\/artifactId>/g" "$OLD_NAME/pom.xml"
        fi
        
        # Rename the directory
        echo "  Renaming directory: $OLD_NAME -> $NEW_NAME"
        mv "$OLD_NAME" "$NEW_NAME"
        
        echo "  Completed: $NEW_NAME"
    else
        echo "Warning: Directory $OLD_NAME not found"
    fi
done

echo ""
echo "Now updating parent POM references..."

# Update parent POM file to reference new module names
PARENT_POM="pom.xml"
if [ -f "$PARENT_POM" ]; then
    echo "Updating parent POM module references..."
    
    # Replace each old module name with new name
    for plugin_pair in "${STEP_PLUGINS[@]}"; do
        OLD_NAME=$(echo "$plugin_pair" | cut -d: -f1)
        NEW_NAME=$(echo "$plugin_pair" | cut -d: -f2)
        
        sed -i '' "s/<module>$OLD_NAME<\/module>/<module>$NEW_NAME<\/module>/g" "$PARENT_POM"
        echo "  Updated parent POM: $OLD_NAME -> $NEW_NAME"
    done
    
    echo "Parent POM updated successfully"
else
    echo "Warning: Parent POM not found at $PARENT_POM"
fi

echo ""
echo "Plugin renaming completed successfully!"
echo "Renamed directories:"
for plugin_pair in "${STEP_PLUGINS[@]}"; do
    OLD_NAME=$(echo "$plugin_pair" | cut -d: -f1)
    NEW_NAME=$(echo "$plugin_pair" | cut -d: -f2)
    echo "  $OLD_NAME -> $NEW_NAME"
done
