# Minerva for Android

An unofficial, **read-only** Android app for **Argo DidUp Famiglia**, the Italian school register: one dashboard per child of a parent account (homework for the next school day, tests and oral exams, the rest of the week, grades, notices to sign in the DidUp app, absences and notes).

- **Read-only by construction**: the client allows only `POST login`, `GET profilo` and `POST dashboard/dashboard` (`Argo.READ_ONLY_CALLS`); everything else is refused before it is sent. The app never acknowledges notices, gives consents, justifies absences or downloads attachments.
- **No server, no data collected**: the app talks only to Argo over HTTPS. Credentials are stored on the phone, the password encrypted with an AES-GCM key in the Android Keystore; school data stays in memory.
- **No AI**: it shows the register, nothing else.

> Not affiliated with Argo Software. The Argo API used by the official app is not public: it can change at any time and break the app.

## Build and test

Plain Java, no Gradle, no AndroidX. Windows uses PowerShell; Linux uses Bash 4.4+. Both builds require JDK 17+ and the Android SDK (platform 36 and build-tools 36.0.0).

```powershell
powershell -ExecutionPolicy Bypass -File build-apk.ps1 -Project app -Name Minerva           # app/build/Minerva.apk
powershell -ExecutionPolicy Bypass -File build-apk.ps1 -Project app -Name Minerva -Install  # and install over adb
bash test.sh                                                                                # core tests on a JVM
```

On Linux, install `zip`, set `ANDROID_HOME` to your Android SDK directory (or use `ANDROID_SDK_ROOT`), and run:

```bash
export ANDROID_HOME="$HOME/Android/Sdk"  # adjust to your SDK location
# Install the required SDK components with the official Android command-line tools.
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "platforms;android-36" "build-tools;36.0.0" "platform-tools"
bash test.sh
bash build-apk.sh --project app --name Minerva            # app/build/Minerva.apk
bash build-apk.sh --project app --name Minerva --install  # also install over adb
bash build-apk.sh --project app --name Minerva --release  # use the release key
```

`JAVA_HOME` is optional when `java`, `javac` and `keytool` are already on `PATH`. The Bash script checks the APK signature and ZIP alignment after building. Debug builds use `~/.android/debug.keystore` (override with `MINERVA_DEBUG_KEYSTORE`); they cannot update an app installed with the release key. Optional `ANDROID_PLATFORM` and `ANDROID_BUILD_TOOLS` variables select alternative installed SDK versions; the target SDK remains 36. Run `bash build-apk.sh --help` for all options.

**Release builds** (`-Release` in PowerShell, `--release` in Bash) are signed with the release key. The keystore lives outside every repository and is backed up separately; its path and password come from the environment (`MINERVA_KEYSTORE`, `MINERVA_KEYSTORE_PASSWORD`). Losing the keystore means installed apps can no longer be updated. Release certificate SHA-256: `1D:68:CB:99:75:9B:67:E7:84:B1:15:69:E4:40:B0:56:D2:F7:AB:57:79:6B:A7:86:49:44:58:22:F3:8A:6E:D0`.

**Automatic releases**: pushing a tag `vX.Y.Z` runs `.github/workflows/release.yml`, which tests, builds the APK signed with the release key (secrets `MINERVA_KEYSTORE_B64` and `MINERVA_KEYSTORE_PASSWORD`) and attaches it to the GitHub release. Bump `versionCode` and `versionName` in `app/AndroidManifest.xml` first.

| Path | Content |
|---|---|
| `app/src/.../core/` | Plain Java: `Json`, `Http` (no redirects followed), `Argo` (OAuth2 PKCE, app login keeping every child's profile, dashboard), `Report`, `Cards` (Italian cards) |
| `app/src/.../MainActivity.java`, `SettingsActivity.java`, `Settings.java` | Dashboards with swipe and pull to refresh; settings with Keystore encryption |
| `app/test/.../TestMain.java` | Core tests (JSON, report against `fixtures/expected_report.json`, read-only allowlist, full login against a fake Argo, cards) |
| `fixtures/` | Invented data only (students «Alice» and «Bianca Maria Rossi») |

## Credits

- **[argo-cli](https://github.com/d4niele/argo-cli)** by **Daniele Rizzo** ([@d4niele](https://github.com/d4niele)): the command line client for DidUp Famiglia this app grew from; its section logic (homework, grades, reminders, notices, absences, notes) is the basis of `Report`.
- [didupwrapper](https://github.com/Rocciadura/didupAPI-wrapper) (MIT) and [portaleargo-api](https://github.com/DTrombett/portaleargo-api) (MIT): the reverse-engineered protocol of the official app (OAuth2 PKCE flow, app login, profiles, dashboard).

Built by Lorenzo Paladini with Daniele Rizzo, with help from Claude Code.
