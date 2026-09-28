#!/usr/bin/env bash
# Prints a comma-separated list of Maven modules touched between BASE and HEAD,
# e.g. "core,engine,plugins/json". Empty output means no Java module changed.
set -euo pipefail
BASE="${1:?base ref}"
HEAD="${2:-HEAD}"
git diff --name-only "$BASE"..."$HEAD" | while read -r f; do
  case "$f" in
    plugins/*/*) echo "$f" | cut -d/ -f1-2 ;;
    core/*|engine/*|engine-ext/*|dbdialog/*|ui/*|utilities/*|assemblies/*) echo "$f" | cut -d/ -f1 ;;
  esac
done | sort -u | while read -r m; do [ -f "$m/pom.xml" ] && echo "$m"; done | paste -sd, -
