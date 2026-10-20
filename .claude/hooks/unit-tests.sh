#!/usr/bin/env bash
# PostToolUse: run the JVM unit tests after edits to the logic they cover; UI files are skipped (Gradle is memory-heavy)
file=$(node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>console.log((JSON.parse(s).tool_input||{}).file_path||""))')
case "$file" in
  *Elm.kt|*Pid.kt|*Checks.kt|*Poller.kt|*Db.kt|*Sync.kt|*Recorder.kt|*Sim.kt|*Test.kt) ;;
  *) exit 0 ;;
esac
export JAVA_HOME="C:/Users/alex/Android/jdk-17.0.20.1+1"
cd "$CLAUDE_PROJECT_DIR" || exit 0
out=$(/c/Users/alex/Android/gradle-8.11.1/bin/gradle -q test 2>&1) || { echo "$out" | tail -40 >&2; exit 2; }
