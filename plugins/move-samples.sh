#!/bin/bash

# Script to move sample transformations and jobs from assemblies/samples to their respective plugin directories
# This organizes samples by their corresponding step plugins for better maintainability

SAMPLES_DIR="/Users/ramaizmansoor/Developer/pentaho-kettle/assemblies/samples/src/main/resources"
PLUGINS_DIR="/Users/ramaizmansoor/Developer/pentaho-kettle/plugins"

echo "Moving sample files from assemblies/samples to plugin directories..."
echo "=================================================="

# Function to create samples directory in plugin if it doesn't exist
create_plugin_samples_dir() {
    local plugin_name="$1"
    local plugin_dir="$PLUGINS_DIR/$plugin_name"
    
    if [ -d "$plugin_dir" ]; then
        local samples_dir="$plugin_dir/samples"
        mkdir -p "$samples_dir"
        echo "Created samples directory: $samples_dir"
        echo "$samples_dir"
    else
        echo "Plugin directory not found: $plugin_dir" >&2
        echo ""
    fi
}

# Function to move files and create directory structure
move_sample_files() {
    local source_dir="$1"
    local target_dir="$2"
    local description="$3"
    
    if [ -d "$source_dir" ] && [ -d "$target_dir" ]; then
        echo "Moving $description..."
        find "$source_dir" -type f \( -name "*.ktr" -o -name "*.kjb" \) -exec cp {} "$target_dir/" \;
        local file_count=$(find "$source_dir" -type f \( -name "*.ktr" -o -name "*.kjb" \) | wc -l)
        echo "  ✓ Successfully moved $file_count files from $source_dir to $target_dir"
    elif [ -d "$target_dir" ]; then
        echo "  ⚠ Source directory $source_dir not found"
    else
        echo "  ⚠ Target directory $target_dir not created"
    fi
}

# Function to copy specific files by pattern
copy_files_by_pattern() {
    local pattern="$1"
    local target_dir="$2"
    local description="$3"
    
    if [ -d "$target_dir" ]; then
        local file_count=$(find "$SAMPLES_DIR/transformations" -name "*$pattern*" -type f | wc -l)
        if [ "$file_count" -gt 0 ]; then
            find "$SAMPLES_DIR/transformations" -name "*$pattern*" -type f -exec cp {} "$target_dir/" \;
            echo "  ✓ Moved $file_count $description files"
        else
            echo "  - No $description files found"
        fi
    fi
}

# Move mapping samples
echo "Processing mapping plugin samples..."
MAPPING_SAMPLES=$(create_plugin_samples_dir "mapping")
if [ -n "$MAPPING_SAMPLES" ]; then
    move_sample_files "$SAMPLES_DIR/transformations/mapping" "$MAPPING_SAMPLES" "Mapping plugin samples"
fi

# Move job executor samples
echo "Processing job executor plugin samples..."
JOBEXEC_SAMPLES=$(create_plugin_samples_dir "jobexecutor")
if [ -n "$JOBEXEC_SAMPLES" ]; then
    move_sample_files "$SAMPLES_DIR/transformations/job-executor" "$JOBEXEC_SAMPLES" "Job Executor plugin samples"
fi

# Move metadata injection samples
echo "Processing meta inject plugin samples..."
METAINJECT_SAMPLES=$(create_plugin_samples_dir "meta-inject")
if [ -n "$METAINJECT_SAMPLES" ]; then
    move_sample_files "$SAMPLES_DIR/transformations/meta-inject" "$METAINJECT_SAMPLES" "Meta Inject plugin samples"
    move_sample_files "$SAMPLES_DIR/transformations/metadata-injection-example" "$METAINJECT_SAMPLES" "Metadata Injection example"
fi

# Move transformation executor samples
echo "Processing transformation executor samples..."
TRANSEXEC_SAMPLES=$(create_plugin_samples_dir "transformation-executor")
if [ -n "$TRANSEXEC_SAMPLES" ]; then
    move_sample_files "$SAMPLES_DIR/transformations/transformation-executor" "$TRANSEXEC_SAMPLES" "Transformation Executor samples"
fi

# Move specific step samples based on file names
echo "Processing individual step samples..."

# Calculator samples
echo "Processing calculator plugin samples..."
CALC_SAMPLES=$(create_plugin_samples_dir "calculator")
if [ -n "$CALC_SAMPLES" ]; then
    copy_files_by_pattern "Calculator" "$CALC_SAMPLES" "Calculator step"
fi

# CSV Input samples
echo "Processing CSV input plugin samples..."
CSV_SAMPLES=$(create_plugin_samples_dir "csvinput")
if [ -n "$CSV_SAMPLES" ]; then
    copy_files_by_pattern "CSV Input" "$CSV_SAMPLES" "CSV Input step"
fi

# Text file input/output samples
echo "Processing text file input plugin samples..."
TEXTINPUT_SAMPLES=$(create_plugin_samples_dir "textfileinput")
if [ -n "$TEXTINPUT_SAMPLES" ]; then
    copy_files_by_pattern "Textfile input" "$TEXTINPUT_SAMPLES" "Text File Input step"
    copy_files_by_pattern "TextInput" "$TEXTINPUT_SAMPLES" "Text Input step"
fi

echo "Processing text file output plugin samples..."
TEXTOUTPUT_SAMPLES=$(create_plugin_samples_dir "textfileoutput")
if [ -n "$TEXTOUTPUT_SAMPLES" ]; then
    copy_files_by_pattern "Text File Output" "$TEXTOUTPUT_SAMPLES" "Text File Output step"
fi

# Table input/output samples
echo "Processing table input plugin samples..."
TABLEINPUT_SAMPLES=$(create_plugin_samples_dir "tableinput")
if [ -n "$TABLEINPUT_SAMPLES" ]; then
    copy_files_by_pattern "Table Input" "$TABLEINPUT_SAMPLES" "Table Input step"
fi

echo "Processing table output plugin samples..."
TABLEOUTPUT_SAMPLES=$(create_plugin_samples_dir "tableoutput")
if [ -n "$TABLEOUTPUT_SAMPLES" ]; then
    copy_files_by_pattern "Table Output" "$TABLEOUTPUT_SAMPLES" "Table Output step"
fi

# Excel samples
echo "Processing excel plugin samples..."
EXCEL_SAMPLES=$(create_plugin_samples_dir "excel")
if [ -n "$EXCEL_SAMPLES" ]; then
    copy_files_by_pattern "Excel" "$EXCEL_SAMPLES" "Excel step"
fi

# JSON samples
echo "Processing JSON plugin samples..."
JSON_SAMPLES=$(create_plugin_samples_dir "json")
if [ -n "$JSON_SAMPLES" ]; then
    copy_files_by_pattern "JSON" "$JSON_SAMPLES" "JSON step"
    copy_files_by_pattern "Json" "$JSON_SAMPLES" "Json step"
fi

# XML samples
echo "Processing XML plugin samples..."
XML_SAMPLES=$(create_plugin_samples_dir "xml")
if [ -n "$XML_SAMPLES" ]; then
    copy_files_by_pattern "XML" "$XML_SAMPLES" "XML step"
fi

# Group by samples
echo "Processing group by plugin samples..."
GROUPBY_SAMPLES=$(create_plugin_samples_dir "groupby")
if [ -n "$GROUPBY_SAMPLES" ]; then
    copy_files_by_pattern "Group By" "$GROUPBY_SAMPLES" "Group By step"
    copy_files_by_pattern "Group by" "$GROUPBY_SAMPLES" "Group by step"
    copy_files_by_pattern "Memory Group By" "$GROUPBY_SAMPLES" "Memory Group By step"
fi

# Select values samples
echo "Processing select values plugin samples..."
SELECT_SAMPLES=$(create_plugin_samples_dir "selectvalues")
if [ -n "$SELECT_SAMPLES" ]; then
    copy_files_by_pattern "Select Values" "$SELECT_SAMPLES" "Select Values step"
    copy_files_by_pattern "Select values" "$SELECT_SAMPLES" "Select values step"
fi

# Filter rows samples
echo "Processing filter rows plugin samples..."
FILTER_SAMPLES=$(create_plugin_samples_dir "filterrows")
if [ -n "$FILTER_SAMPLES" ]; then
    copy_files_by_pattern "Filter" "$FILTER_SAMPLES" "Filter step"
    copy_files_by_pattern "Java Filter" "$FILTER_SAMPLES" "Java Filter step"
fi

# Copy some general samples to multiple relevant plugins
echo "Copying general samples to relevant plugins..."

# Copy data generator samples
DATA_GEN_DIR="$SAMPLES_DIR/transformations/data-generator"
if [ -d "$DATA_GEN_DIR" ]; then
    DATAGRID_SAMPLES=$(create_plugin_samples_dir "datagrid")
    if [ -n "$DATAGRID_SAMPLES" ]; then
        cp -r "$DATA_GEN_DIR"/* "$DATAGRID_SAMPLES/" 2>/dev/null
        echo "  ✓ Copied data generator samples to datagrid plugin"
    fi
fi

# Handle job samples
echo "Processing job samples..."
JOBS_SOURCE="$SAMPLES_DIR/jobs"
if [ -d "$JOBS_SOURCE" ]; then
    # Create a general jobs samples directory
    GENERAL_JOBS="$PLUGINS_DIR/core/samples/jobs"
    mkdir -p "$GENERAL_JOBS"
    cp -r "$JOBS_SOURCE"/* "$GENERAL_JOBS/" 2>/dev/null
    echo "  ✓ Moved job samples to core plugin"
fi

# Handle database samples
echo "Processing database samples..."
DB_SOURCE="$SAMPLES_DIR/db"
if [ -d "$DB_SOURCE" ]; then
    # Copy to database-related plugins
    for plugin in "databasejoin" "databaselookup" "tableinput" "tableoutput"; do
        PLUGIN_SAMPLES=$(create_plugin_samples_dir "$plugin")
        if [ -n "$PLUGIN_SAMPLES" ]; then
            mkdir -p "$PLUGIN_SAMPLES/db"
            cp -r "$DB_SOURCE"/* "$PLUGIN_SAMPLES/db/" 2>/dev/null
        fi
    done
    echo "  ✓ Copied database samples to database-related plugins"
fi

echo "=================================================="
echo "Sample file migration completed!"
echo ""
echo "Summary of created plugin sample directories:"
find "$PLUGINS_DIR" -name "samples" -type d | sort

echo ""
echo "You can now remove the original samples from assemblies/samples if desired."
echo "The samples are now organized by their corresponding step plugins."
