# Compila una delle app in phone/ senza Gradle: javac -> d8 -> aapt2 -> zipalign -> apksigner.
# Uso: powershell -ExecutionPolicy Bypass -File phone\build-apk.ps1 -Project phone\cpu-limiter -Name LimitiCPU [-Install]
# Il progetto contiene AndroidManifest.xml, src\ (Java) e, se serve, res\ (risorse, es. l'icona).
# Richiede Android Studio (Java inclusa) e l'SDK in %LOCALAPPDATA%\Android\Sdk.
param(
    [Parameter(Mandatory)][string]$Project,
    [Parameter(Mandatory)][string]$Name,
    [switch]$Install
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
# debug.keystore standard di Android: password pubblica "android", solo per build di sviluppo
& "$bt\apksigner.bat" sign --ks $keystore --ks-pass pass:android --out $apk "$out\aligned.apk"
if ($LASTEXITCODE) { throw 'apksigner fallito' }
Write-Host "APK: $apk"

if ($Install) {
    & "$env:LOCALAPPDATA\Android\platform-tools\adb.exe" install -r $apk
}
