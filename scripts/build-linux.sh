#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ "$(uname -s)" != "Linux" || "$(uname -m)" != "x86_64" ]]; then
  echo "WFTools-PC Linux 发行构建仅支持 Linux x86_64。" >&2
  exit 2
fi
if ! command -v java >/dev/null || ! command -v javac >/dev/null || ! command -v jpackage >/dev/null; then
  echo "缺少 JDK 21（java、javac 或 jpackage）。" >&2
  exit 2
fi
java_major="$(java -version 2>&1 | sed -n '1s/.*version "\([0-9]*\).*/\1/p')"
if [[ "$java_major" != "21" ]]; then
  echo "需要 JDK 21，当前主版本为 ${java_major:-未知}。" >&2
  exit 2
fi

linux_gradle_cache="${WFTOOLS_LINUX_GRADLE_HOME:-${XDG_CACHE_HOME:-$HOME/.cache}/wftools-gradle}"
mkdir -p "$linux_gradle_cache"
export GRADLE_USER_HOME="$linux_gradle_cache"

tasks=("$@")
if [[ ${#tasks[@]} -eq 0 ]]; then
  tasks=(":watch-core:test" ":watch-renderer:test" ":desktopApp:test" ":desktopApp:createDistributable")
fi
cd "$repository_root"
exec java -Dorg.gradle.appname=gradlew \
  -classpath "$repository_root/gradle/wrapper/gradle-wrapper.jar" \
  org.gradle.wrapper.GradleWrapperMain "${tasks[@]}" --console=plain -Pwftools.buildPlatform=linux-x64
