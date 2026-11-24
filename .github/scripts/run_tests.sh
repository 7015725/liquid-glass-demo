#!/usr/bin/env bash
# Runs one Gradle test task and retries once when Robolectric's runtime jar could not be
# downloaded. MavenArtifactFetcher IOException is a known transient on fresh CI runners and
# otherwise fails every Robolectric test class in the task.
set -uo pipefail

task="${1:?usage: run_tests.sh <gradle test task>}"
log="$(mktemp)"
status=0

./gradlew "$task" --stacktrace --no-configuration-cache 2>&1 | tee "$log" || status=$?

if [ "$status" -ne 0 ] && grep -qE 'MavenArtifactFetcher|android-all|Could not (download|GET)' "$log"; then
  echo "::warning::Retrying $task after a Robolectric dependency download failure"
  ./gradlew "$task" --stacktrace --no-configuration-cache
  status=$?
fi

rm -f "$log"
exit "$status"
