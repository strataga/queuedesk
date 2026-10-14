#!/bin/sh
# Shared JDK 25 selection, sourced (never executed) by every lab wrapper.
# Prefers the verified installed JDK 25 on this Mac via java_home(1), then
# verifies the version string really says 25 — a silent wrong-major JDK
# would fail later with much worse errors. Fails fast with a named fix.
if [ -z "${JAVA_HOME:-}" ] && [ -x /usr/libexec/java_home ]; then
  JAVA_HOME=$(/usr/libexec/java_home -v 25 2>/dev/null) || true
fi
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
  echo "Set JAVA_HOME to a JDK 25 installation." >&2; exit 1
fi
version=$("$JAVA_HOME/bin/java" -version 2>&1 | head -n 1)
case "$version" in *'version "25'* ) ;; *) echo "QueueDesk requires JDK 25; got $version" >&2; exit 1 ;; esac
export JAVA_HOME
PATH="$JAVA_HOME/bin:$PATH"
export PATH
