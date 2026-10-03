# Builds the app without Gradle: javac -> d8 -> aapt2 -> zipalign -> apksigner.
# Usage: powershell -ExecutionPolicy Bypass -File build-apk.ps1 -Project app -Name Minerva [-Install] [-Release]
# The project holds AndroidManifest.xml, src\ (Java) and res\ (resources).
# Needs Android Studio (Java included) and the SDK in %LOCALAPPDATA%\Android\Sdk.
# -Release signs with the release key: keystore path in MINERVA_KEYSTORE, password in MINERVA_KEYSTORE_PASSWORD
# (both from the environment, never committed). Without it the APK is signed with Android's debug key.
param(
    [Parameter(Mandatory)][string]$Project,
    [Parameter(Mandatory)][string]$Name,
    [switch]$Install,
    [switch]$Release
)
$ErrorActionPreference = 'Stop'

$here = (Resolve-Path $Project).Path
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$jbr = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files\Android\Android Studio\jbr' }
$env:JAVA_HOME = $jbr  # d8.bat e apksigner.bat cercano Java qui
$bt = (Get-ChildItem "$sdk\build-tools" | Sort-Object Name | Select-Object -Last 1).FullName
$androidJar = (Get-ChildItem "$sdk\platforms" | Sort-Object Name | Select-Object -Last 1).FullName + '\android.jar'
$out = Join-Path $here 'build'
$apk = "$out\$Name.apk"
$keystore = "$env:USERPROFILE\.android\debug.keystore"

Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "$out\classes", "$out\dex" | Out-Null

# @(...): con un solo file PowerShell restituirebbe una stringa, e lo splatting la spezzerebbe in caratteri
$sources = @(Get-ChildItem "$here\src" -Recurse -Filter *.java | ForEach-Object FullName)
& "$jbr\bin\javac.exe" -encoding UTF-8 --release 11 -classpath $androidJar -d "$out\classes" @sources
if ($LASTEXITCODE) { throw 'javac fallito' }

$classes = @(Get-ChildItem "$out\classes" -Recurse -Filter *.class | ForEach-Object FullName)
& "$bt\d8.bat" --lib $androidJar --min-api 30 --output "$out\dex" @classes
if ($LASTEXITCODE) { throw 'd8 fallito' }

$resources = @()
if (Test-Path "$here\res") {
    & "$bt\aapt2.exe" compile --dir "$here\res" -o "$out\res.zip"
    if ($LASTEXITCODE) { throw 'aapt2 compile fallito' }
    $resources = @("$out\res.zip")
}
& "$bt\aapt2.exe" link --manifest "$here\AndroidManifest.xml" -I $androidJar `
    --min-sdk-version 30 --target-sdk-version 36 -o "$out\unsigned.apk" @resources
if ($LASTEXITCODE) { throw 'aapt2 link fallito' }

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$out\unsigned.apk", 'Update')
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$out\dex\classes.dex", 'classes.dex') | Out-Null
$zip.Dispose()

& "$bt\zipalign.exe" -p -f 4 "$out\unsigned.apk" "$out\aligned.apk"
if ($LASTEXITCODE) { throw 'zipalign fallito' }

if (-not (Test-Path $keystore)) {
    New-Item -ItemType Directory -Force (Split-Path $keystore) | Out-Null
    & "$jbr\bin\keytool.exe" -genkeypair -keystore $keystore -storepass android -keypass android `
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Android Debug,O=Android,C=US'
}
if ($Release) {
    if (-not $env:MINERVA_KEYSTORE -or -not $env:MINERVA_KEYSTORE_PASSWORD) { throw 'Release build: set MINERVA_KEYSTORE and MINERVA_KEYSTORE_PASSWORD' }
    & "$bt\apksigner.bat" sign --ks $env:MINERVA_KEYSTORE --ks-key-alias minerva --ks-pass env:MINERVA_KEYSTORE_PASSWORD --out $apk "$out\aligned.apk"
} else {
    # Android's standard debug.keystore: public password "android", development builds only
    & "$bt\apksigner.bat" sign --ks $keystore --ks-pass pass:android --out $apk "$out\aligned.apk"
}
if ($LASTEXITCODE) { throw 'apksigner fallito' }
Write-Host "APK: $apk"

if ($Install) {
    & "$env:LOCALAPPDATA\Android\platform-tools\adb.exe" install -r $apk
}
