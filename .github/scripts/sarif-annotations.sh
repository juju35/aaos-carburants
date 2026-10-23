#!/usr/bin/env bash
# Print one GitHub annotation per SARIF finding (errors as ::error, the rest as ::warning),
# so detekt and Android lint findings show on the pull request and in the run summary.
set -euo pipefail
for sarif in "$@"; do
  [ -f "$sarif" ] || continue
  jq -r --arg ws "${GITHUB_WORKSPACE:-$PWD}/" '
    .runs[].results[]? |
    (.locations[0].physicalLocation // {}) as $loc |
    (($loc.artifactLocation.uri // "") | ltrimstr("file://") | ltrimstr($ws)) as $file |
    "::\(if .level == "error" then "error" else "warning" end) file=\($file),line=\($loc.region.startLine // 1),col=\($loc.region.startColumn // 1),title=\(.ruleId)::\(.message.text | gsub("\n"; " "))"
  ' "$sarif"
done
