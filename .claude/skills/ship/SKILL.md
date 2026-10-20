---
name: ship
description: Test, commit, tag vX.Y.Z, push, wait for the signed GitHub release, optionally redeploy the Zima hub
disable-model-invocation: true
---

Release yaris-doctor. Argument: the version (`v0.9.0`) and optionally `hub` to also redeploy the hub. No version given: read `git tag --sort=-v:refname | head -1` and propose the next patch version.

1. `git status`. Uncommitted changes: show them and commit only with the user's yes. Commits use the user's identity and end with the Co-Authored-By trailer.
2. Build and test locally (Git Bash):
   ```bash
   export JAVA_HOME="C:/Users/alex/Android/jdk-17.0.20.1+1"; /c/Users/alex/Android/gradle-8.11.1/bin/gradle -q test assembleDebug; /c/Users/alex/Android/gradle-8.11.1/bin/gradle --stop
   ```
   Failure: stop and report.
3. `git tag vX.Y.Z && git push origin main vX.Y.Z`.
4. Wait for the tag's workflow: `gh run list --branch vX.Y.Z --limit 1`, then `gh run watch <id> --exit-status` in the background.
5. Check `gh release view vX.Y.Z --json assets -q '.assets[].name'` lists `yaris-doctor-vX.Y.Z.apk`.
6. Only with `hub`: push `../yaris-doctor-hub` if it has unpushed commits (ask first), wait for its image build, then:
   ```bash
   ssh -o BatchMode=yes zima 'docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v /var/lib/casaos/apps:/var/lib/casaos/apps -w /var/lib/casaos/apps/yaris-hub docker:27.3.1-cli compose up -d --pull always --force-recreate'
   ```
   If key auth fails, stop and tell the user; never retry.

Never regenerate or touch the signing key. Report: commit, tag, CI result, APK name.
