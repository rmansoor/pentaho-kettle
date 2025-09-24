#!/bin/bash

# Comprehensive script to move sample transformations and jobs from assemblies/samples to plugin directories
SAMPLES_SOURCE="/Users/ramaizmansoor/Developer/pentaho-kettle/assemblies/samples/src/main/resources"
PLUGINS_DIR="/Users/ramaizmansoor/Developer/pentaho-kettle/plugins"

echo "Sample Migration Script"
echo "======================"
echo "Source: $SAMPLES_SOURCE"
echo "Target: $PLUGINS_DIR"
echo ""

# Create function to setup plugin samples directory
setup_plugin_samples() {
    local plugin_name="$1"
    local plugin_path="$PLUGINS_DIR/$plugin_name"
    
    if [ -d "$plugin_path" ]; then
        mkdir -p "$plugin_path/samples/transformations"
        mkdir -p "$plugin_path/samples/jobs"
        mkdir -p "$plugin_path/samples/data"
        echo "Created samples structure for $plugin_name"
        return 0
    else
        echo "Plugin $plugin_name not found at $plugin_path"
        return 1
    fi
}

# Function to copy files by pattern
copy_samples() {
    local pattern="$1"
    local plugin_name="$2"
    local sample_type="$3"
    
    if setup_plugin_samples "$plugin_name"; then
        local target_dir="$PLUGINS_DIR/$plugin_name/samples/$sample_type"
        local count=0
        
        # Find and copy matching files
        while IFS= read -r -d '' file; do
            if [ -f "$file" ]; then
                cp "$file" "$target_dir/"
                ((count++))
            fi
        done < <(find "$SAMPLES_SOURCE/$sample_type" -name "*$pattern*" -type f -print0 2>/dev/null)
        
        if [ $count -gt 0 ]; then
            echo "  ✓ Copied $count $pattern sample(s) to $plugin_name"
        else
            echo "  - No $pattern samples found"
        fi
    fi
}

# Function to copy directory samples
copy_directory_samples() {
    local dir_name="$1"
    local plugin_name="$2"
    local sample_type="$3"
    
    if setup_plugin_samples "$plugin_name"; then
        local source_dir="$SAMPLES_SOURCE/$sample_type/$dir_name"
        local target_dir="$PLUGINS_DIR/$plugin_name/samples/$sample_type"
        
        if [ -d "$source_dir" ]; then
            cp -r "$source_dir"/* "$target_dir/" 2>/dev/null
            local file_count=$(find "$source_dir" -type f | wc -l)
            echo "  ✓ Copied $file_count files from $dir_name to $plugin_name"
        else
            echo "  - Directory $source_dir not found"
        fi
    fi
}

echo "Processing step-specific samples..."
echo "-----------------------------------"

# Move mapping samples
copy_directory_samples "mapping" "mapping" "transformations"

# Move job executor samples  
copy_directory_samples "job-executor" "jobexecutor" "transformations"

# Move meta inject samples
copy_directory_samples "meta-inject" "meta-inject" "transformations"
copy_directory_samples "metadata-injection-example" "meta-inject" "transformations"

# Move transformation executor samples
copy_directory_samples "transformation-executor" "transformation-executor" "transformations"

# Move data generator samples
copy_directory_samples "data-generator" "datagrid" "transformations"

# Move dynamic table samples
copy_directory_samples "dynamic-table" "tableinput" "transformations"

echo ""
echo "Processing pattern-based samples..."
echo "----------------------------------"

# Calculator samples
copy_samples "Calculator" "calculator" "transformations"

# CSV Input samples
copy_samples "CSV Input" "csvinput" "transformations"

# Text file samples
copy_samples "Textfile input" "textfileinput" "transformations"
copy_samples "TextInput" "textfileinput" "transformations"
copy_samples "Text File Output" "textfileoutput" "transformations"

# Table samples
copy_samples "Table Output" "tableoutput" "transformations"

# Excel samples
copy_samples "Excel" "excel" "transformations"

# JSON samples
copy_samples "JSON" "json" "transformations"
copy_samples "Json" "json" "transformations"

# XML samples
copy_samples "XML" "xml" "transformations"

# Group By samples
copy_samples "Group By" "groupby" "transformations"
copy_samples "Group by" "groupby" "transformations"
copy_samples "Memory Group By" "groupby" "transformations"

# Select Values samples
copy_samples "Select Values" "selectvalues" "transformations"
copy_samples "Select values" "selectvalues" "transformations"

# Filter samples
copy_samples "Java Filter" "filterrows" "transformations"

# JavaScript samples
copy_samples "JavaScript" "javascript" "transformations"

# Sort samples
copy_samples "sort" "sort" "transformations"

# Other step samples
copy_samples "Unique" "unique" "transformations"
copy_samples "Stream lookup" "streamlookup" "transformations"
copy_samples "Merge" "mergejoin" "transformations"
copy_samples "Web Services" "webservices" "transformations"
copy_samples "HTTP Client" "httpclient" "transformations"

echo ""
echo "Processing job samples..."
echo "------------------------"

# Copy all job samples to core plugin
if setup_plugin_samples "core"; then
    if [ -d "$SAMPLES_SOURCE/jobs" ]; then
        cp -r "$SAMPLES_SOURCE/jobs"/* "$PLUGINS_DIR/core/samples/jobs/" 2>/dev/null
        local job_count=$(find "$SAMPLES_SOURCE/jobs" -name "*.kjb" | wc -l)
        echo "  ✓ Copied job samples to core plugin ($job_count job files)"
    fi
fi

echo ""
echo "Processing database samples..."
echo "-----------------------------"

# Copy database samples to relevant plugins
if [ -d "$SAMPLES_SOURCE/db" ]; then
    for plugin in "databasejoin" "databaselookup" "tableinput" "tableoutput"; do
        if setup_plugin_samples "$plugin"; then
            cp -r "$SAMPLES_SOURCE/db"/* "$PLUGINS_DIR/$plugin/samples/data/" 2>/dev/null
            echo "  ✓ Copied database samples to $plugin"
        fi
    done
fi

echo ""
echo "Processing additional file samples..."
echo "------------------------------------"

# Copy files directory to relevant plugins
if [ -d "$SAMPLES_SOURCE/transformations/files" ]; then
    for plugin in "textfileinput" "textfileoutput" "csvinput" "excel"; do
        if setup_plugin_samples "$plugin"; then
            cp -r "$SAMPLES_SOURCE/transformations/files"/* "$PLUGINS_DIR/$plugin/samples/data/" 2>/dev/null
            echo "  ✓ Copied file samples to $plugin"
        fi
    done
fi

echo ""
echo "Migration Summary"
echo "================"
echo "Created plugin sample directories:"
find "$PLUGINS_DIR" -name "samples" -type d | sort

echo ""
echo "Sample files by plugin:"
for plugin_dir in "$PLUGINS_DIR"/*/samples; do
    if [ -d "$plugin_dir" ]; then
        plugin_name=$(basename $(dirname "$plugin_dir"))
        file_count=$(find "$plugin_dir" -name "*.ktr" -o -name "*.kjb" | wc -l)
        if [ "$file_count" -gt 0 ]; then
            echo "  $plugin_name: $file_count files"
        fi
    fi
done

echo ""
echo "Sample migration completed!"
echo "You can now remove the original assemblies/samples directory if desired."
