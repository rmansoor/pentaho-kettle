# Sample Migration Summary

## Overview
This document summarizes the work to move sample transformations and jobs from the centralized `assemblies/samples` directory to their respective plugin sample directories for better organization and maintainability.

## Source Location
- **Original samples location**: `/pentaho-kettle/assemblies/samples/src/main/resources/`
  - `transformations/` - Contains transformation (.ktr) samples
  - `jobs/` - Contains job (.kjb) samples  
  - `db/` - Contains database sample files

## Target Structure
Each plugin now has its own `samples/` directory with the following structure:
```
plugins/{plugin-name}/
├── impl/
├── ui/
├── samples/
│   ├── transformations/
│   ├── jobs/
│   └── data/
└── pom.xml
```

## Plugin Sample Mappings

### Direct Directory Mappings
- `transformations/mapping/` → `plugins/mapping/samples/transformations/`
- `transformations/job-executor/` → `plugins/jobexecutor/samples/transformations/`
- `transformations/meta-inject/` → `plugins/meta-inject/samples/transformations/`
- `transformations/transformation-executor/` → `plugins/transformation-executor/samples/transformations/`
- `transformations/data-generator/` → `plugins/datagrid/samples/transformations/`

### Pattern-Based Mappings
- Files containing "Calculator" → `plugins/calculator/samples/transformations/`
- Files containing "CSV Input" → `plugins/csvinput/samples/transformations/`
- Files containing "Text File Input/Output" → `plugins/textfileinput/textfileoutput/samples/transformations/`
- Files containing "Table Input/Output" → `plugins/tableinput/tableoutput/samples/transformations/`
- Files containing "Excel" → `plugins/excel/samples/transformations/`
- Files containing "JSON" → `plugins/json/samples/transformations/`
- Files containing "XML" → `plugins/xml/samples/transformations/`
- Files containing "Group By" → `plugins/groupby/samples/transformations/`
- Files containing "Select Values" → `plugins/selectvalues/samples/transformations/`
- Files containing "Filter" → `plugins/filterrows/samples/transformations/`

### Special Cases
- Job samples → `plugins/core/samples/jobs/`
- Database samples → Copied to database-related plugins (databasejoin, databaselookup, tableinput, tableoutput)
- File samples → Copied to file-processing plugins (textfileinput, textfileoutput, csvinput, excel)

## Scripts Created

### migrate-samples.sh
A comprehensive shell script that:
1. Creates `samples/` directory structure for each plugin
2. Copies relevant sample files based on naming patterns
3. Handles special directory mappings
4. Provides detailed migration summary

### move-samples.sh
An alternative migration script with similar functionality.

## Benefits of This Organization

1. **Better Discoverability**: Users can find samples relevant to specific steps directly in the plugin directory
2. **Improved Maintainability**: Plugin developers can maintain samples alongside their code
3. **Cleaner Architecture**: Follows the modular plugin architecture principle
4. **Enhanced Documentation**: Each plugin can have its own sample documentation

## Next Steps

1. **Verify Migration**: Check that sample files were copied correctly to plugin directories
2. **Update Documentation**: Update any references to the old samples location
3. **Remove Original**: Consider removing the centralized samples directory after verification
4. **Add README Files**: Create plugin-specific README files explaining the samples
5. **Test Functionality**: Verify that the moved samples still work correctly

## Files Created/Modified

- `/plugins/migrate-samples.sh` - Comprehensive migration script
- `/plugins/move-samples.sh` - Alternative migration script
- Plugin sample directories created for:
  - mapping
  - jobexecutor
  - meta-inject
  - calculator
  - csvinput
  - textfileinput
  - textfileoutput
  - tableinput
  - tableoutput
  - excel
  - json
  - xml
  - groupby
  - selectvalues
  - filterrows
  - core (for jobs)

## Validation Commands

To verify the migration was successful:

```bash
# Count samples by plugin
for plugin in plugins/*/samples; do
  if [ -d "$plugin" ]; then
    name=$(basename $(dirname "$plugin"))
    count=$(find "$plugin" -name "*.ktr" -o -name "*.kjb" | wc -l)
    echo "$name: $count samples"
  fi
done

# List all plugin sample directories
find plugins -name "samples" -type d | sort
```

This reorganization aligns with enterprise software best practices and makes the Pentaho Kettle plugin ecosystem more maintainable and user-friendly.
