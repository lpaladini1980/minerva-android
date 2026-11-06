#!/usr/bin/env bash
# Linux alternative to build-apk.ps1: javac -> d8 -> aapt2 -> zipalign -> apksigner.
set -euo pipefail
if ((BASH_VERSINFO[0] < 4 || (BASH_VERSINFO[0] == 4 && BASH_VERSINFO[1] < 4))); then
  echo "Error: Bash 4.4+ is required" >&2
  exit 1
fi

usage() {
  cat <<'HELP'
Usage: bash build-apk.sh [--project app] [--name Minerva] [--install] [--release]
Requires Bash 4.4+, JDK 17+, zip, and Android SDK platform 36 / build-tools 36.0.0.
Set ANDROID_HOME (or ANDROID_SDK_ROOT); JAVA_HOME is optional when Java is on PATH.
Release signing uses MINERVA_KEYSTORE and MINERVA_KEYSTORE_PASSWORD (alias minerva).
Optional overrides: ANDROID_PLATFORM (default android-36), ANDROID_BUILD_TOOLS (default 36.0.0),
MINERVA_DEBUG_KEYSTORE (default ~/.android/debug.keystore).
HELP
}
fail() { echo "Error: $*" >&2; exit 1; }
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project="$root/app"
name=Minerva
install=false
release=false
while (($#)); do
  case "$1" in
    --project|--name)
      (($# >= 2)) || fail "Missing value for $1"
      if [[ "$1" == --project ]]; then project="$2"; else name="$2"; fi
      shift 2 ;;
    --install) install=true; shift ;;
    --release) release=true; shift ;;
    -h|--help) usage; exit 0 ;;
    *) fail "Unknown option: $1 (use --help)" ;;
  esac
done
[[ "$name" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] || fail "Invalid APK name"
project="$(cd -- "$project" && pwd)"
[[ -f "$project/AndroidManifest.xml" ]] || fail "Missing AndroidManifest.xml"
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[[ -n "$sdk" ]] || fail "Set ANDROID_HOME to your Android SDK directory"
sdk="$(cd -- "$sdk" && pwd)"
platform="${ANDROID_PLATFORM:-android-36}"
build_tools="${ANDROID_BUILD_TOOLS:-36.0.0}"
bt="$sdk/build-tools/$build_tools"
android_jar="$sdk/platforms/$platform/android.jar"
[[ -f "$android_jar" ]] || fail "Missing $android_jar; install platforms;$platform with sdkmanager"
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
for tool in java javac keytool zip; do command -v "$tool" >/dev/null || fail "Missing $tool"; done
for tool in d8 aapt2 zipalign apksigner; do
  [[ -x "$bt/$tool" ]] || fail "Missing $bt/$tool; install build-tools;$build_tools with sdkmanager"
done
if $install; then [[ -x "$sdk/platform-tools/adb" ]] || fail "Install platform-tools for --install"; fi
if $release; then
  [[ -n "${MINERVA_KEYSTORE:-}" && -n "${MINERVA_KEYSTORE_PASSWORD:-}" ]] || fail "Set MINERVA_KEYSTORE and MINERVA_KEYSTORE_PASSWORD"
  [[ -f "$MINERVA_KEYSTORE" ]] || fail "Release keystore not found"
  export MINERVA_KEYSTORE_PASSWORD
fi

out="$project/build"
mkdir -p "$out"
work="$(mktemp -d "$out/.apk-build-XXXXXX")"
trap 'rm -rf -- "$work"' EXIT
mkdir -p "$work/classes" "$work/dex"
mapfile -d '' -t sources < <(find "$project/src" -type f -name '*.java' -print0)
((${#sources[@]})) || fail "No Java sources found"
javac -encoding UTF-8 --release 11 -classpath "$android_jar" -d "$work/classes" "${sources[@]}"
mapfile -d '' -t classes < <(find "$work/classes" -type f -name '*.class' -print0)
"$bt/d8" --lib "$android_jar" --min-api 30 --output "$work/dex" "${classes[@]}"
resources=()
if [[ -d "$project/res" ]]; then
  "$bt/aapt2" compile --dir "$project/res" -o "$work/res.zip"
  resources=("$work/res.zip")
fi
"$bt/aapt2" link --manifest "$project/AndroidManifest.xml" -I "$android_jar" \
  --min-sdk-version 30 --target-sdk-version 36 -o "$work/unsigned.apk" "${resources[@]}"
# Store every generated DEX file, including multidex output, in the APK root.
(cd -- "$work/dex" && zip -q -0 "$work/unsigned.apk" ./*.dex)
"$bt/zipalign" -f 4 "$work/unsigned.apk" "$work/aligned.apk"
if $release; then
  "$bt/apksigner" sign --ks "$MINERVA_KEYSTORE" --ks-key-alias minerva \
    --ks-pass env:MINERVA_KEYSTORE_PASSWORD --out "$work/signed.apk" "$work/aligned.apk"
else
  debug_keystore="${MINERVA_DEBUG_KEYSTORE:-$HOME/.android/debug.keystore}"
  if [[ ! -f "$debug_keystore" ]]; then
    mkdir -p "$(dirname -- "$debug_keystore")"
    keytool -genkeypair -keystore "$debug_keystore" -storepass android -keypass android \
      -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Android Debug,O=Android,C=US'
  fi
  "$bt/apksigner" sign --ks "$debug_keystore" --ks-key-alias androiddebugkey \
    --ks-pass pass:android --out "$work/signed.apk" "$work/aligned.apk"
fi
"$bt/apksigner" verify "$work/signed.apk"
"$bt/zipalign" -c 4 "$work/signed.apk"
apk="$out/$name.apk"
mv -- "$work/signed.apk" "$apk"
echo "APK: $apk"
if $install; then "$sdk/platform-tools/adb" install -r "$apk"; fi
