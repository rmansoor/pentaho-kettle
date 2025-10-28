#!/bin/bash

# Sample Migration Validation Script
# Run this script to verify that sample files were moved correctly

PLUGINS_DIR="/Users/ramaizmansoor/Developer/pentaho-kettle/plugins"
ASSEMBLIES_DIR="/Users/ramaizmansoor/Developer/pentaho-kettle/assemblies/samples/src/main/resources"

echo "Sample Migration Validation Report"
echo "=================================="
echo ""

echo "1. Checking plugin sample directories created:"
echo "---------------------------------------------"
sample_dirs=$(find "$PLUGINS_DIR" -maxdepth 2 -name "samples" -type d 2>/dev/null | sort)
if [ -n "$sample_dirs" ]; then
    echo "$sample_dirs"
    echo ""
    echo "Total sample directories created: $(echo "$sample_dirs" | wc -l)"
else
    echo "No sample directories found."
fi

echo ""
echo "2. Sample file count by plugin:"
echo "------------------------------"
for plugin_dir in "$PLUGINS_DIR"/*/samples; do
    if [ -d "$plugin_dir" ]; then
        plugin_name=$(basename $(dirname "$plugin_dir"))
        ktr_count=$(find "$plugin_dir" -name "*.ktr" 2>/dev/null | wc -l)
        kjb_count=$(find "$plugin_dir" -name "*.kjb" 2>/dev/null | wc -l)
        total_count=$((ktr_count + kjb_count))
        
        if [ "$total_count" -gt 0 ]; then
            echo "$plugin_name: $ktr_count transformations, $kjb_count jobs (total: $total_count)"
        fi
    fi
done

echo ""
echo "3. Original assemblies sample count:"
echo "-----------------------------------"
if [ -d "$ASSEMBLIES_DIR" ]; then
    orig_ktr=$(find "$ASSEMBLIES_DIR" -name "*.ktr" 2>/dev/null | wc -l)
    orig_kjb=$(find "$ASSEMBLIES_DIR" -name "*.kjb" 2>/dev/null | wc -l)
    echo "Original transformations: $orig_ktr"
    echo "Original jobs: $orig_kjb"
    echo "Original total: $((orig_ktr + orig_kjb))"
else
    echo "Original assemblies directory not found at $ASSEMBLIES_DIR"
fi

echo ""
echo "4. Sample structure verification:"
echo "--------------------------------"
for plugin in mapping jobexecutor calculator csvinput textfileinput textfileoutput tableinput tableoutput; do
    plugin_path="$PLUGINS_DIR/$plugin"
    if [ -d "$plugin_path" ]; then
        samples_path="$plugin_path/samples"
        if [ -d "$samples_path" ]; then
            echo "✓ $plugin has samples directory"
            if [ -d "$samples_path/transformations" ]; then
                echo "  ✓ transformations subdirectory exists"
            fi
            if [ -d "$samples_path/jobs" ]; then
                echo "  ✓ jobs subdirectory exists"
            fi
            if [ -d "$samples_path/data" ]; then
                echo "  ✓ data subdirectory exists"
            fi
        else
            echo "✗ $plugin missing samples directory"
        fi
    else
        echo "✗ $plugin directory not found"
    fi
done

echo ""
echo "5. Recommended next actions:"
echo "---------------------------"
echo "- If sample directories exist but are empty, re-run the migration script"
echo "- Verify that sample files work correctly in their new locations"
echo "- Update any documentation referencing the old samples location"
echo "- Consider removing the original assemblies/samples directory"
echo "- Add plugin-specific README files for the samples"

echo ""
echo "Validation completed."
