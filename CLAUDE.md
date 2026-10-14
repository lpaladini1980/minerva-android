# minerva-android: rules for every session

Minerva for Android: the unofficial, **read-only** Argo DidUp Famiglia app (plain Java, no Gradle, no AndroidX).
Developed by the `pl-dev-minerva` agent, which lives in Paladia (the workspace hub, Paladia#135) with every other
agent and skill; this repository is cloned in `Paladia/repos/minerva-android`. Start Claude Code from Paladia.

- **Read-only, always**: the client allows only `POST login`, `GET profilo` and `POST dashboard/dashboard`
  (`Argo.READ_ONLY_CALLS`). Never add a call that writes, acknowledges, consents, justifies or downloads.
- **Language**: everything in the repository is English; text shown in the app is Italian.
- **Data**: fixtures are invented (`fixtures/`); never real names, grades or messages, in code, tests, issues or commits.
- **Secrets**: the release keystore is outside every repository (OneDrive `Backup\`); its path and password come from
  Paladia's single `.env` (`MINERVA_KEYSTORE`, `MINERVA_KEYSTORE_PASSWORD`); CI secrets `MINERVA_KEYSTORE_B64`,
  `MINERVA_KEYSTORE_PASSWORD`. Never print them.
- **Work (gitflow)**: one issue per piece of work in this repository; branch `feat|fix|docs|chore/<issue>-<slug>` from
  `develop` (default branch); PR into `develop` with `Fixes #<issue>`, CI green; releases `develop` → `main` with a merge
  commit and a tag `vX.Y.Z` (the tag builds the signed APK), only on the PO's «ok»; only the PO merges into `main`.
- **Checks**: `bash test.sh` (core tests on a JVM) and a build with `build-apk.ps1` before every PR.
- Unclear requirement: grill the PO with the `pl-work-grilling` skill, one question per message, each with a recommended answer.
