# Builds the ESJZone reader APK with a standalone toolchain
# (aapt2 + javac + d8 + zipalign + apksigner), no Gradle and no Android Studio.
[CmdletBinding()]
param(
    [string]$JavaHome = 'F:\ARCTIME_PRO_4.5_WIN64\tools\JRE',
    [string]$SdkRoot = 'F:\deepseek harness\sdkwork\sdk'
)

$ErrorActionPreference = 'Stop'
$Root = $PSScriptRoot
$Build = Join-Path $Root 'build'

$Java = Join-Path $JavaHome 'bin\java.exe'
$Javac = Join-Path $JavaHome 'bin\javac.exe'
$Keytool = Join-Path $JavaHome 'bin\keytool.exe'
$Bt = Join-Path $SdkRoot 'build-tools\android-13'
$Aapt2 = Join-Path $Bt 'aapt2.exe'
$ZipAlign = Join-Path $Bt 'zipalign.exe'
$ApkSigner = Join-Path $Bt 'apksigner.bat'
$AndroidJar = Join-Path $SdkRoot 'platforms\android-13\android.jar'
$Jsoup = Join-Path $Root 'libs\jsoup-1.14.3.jar'
$Python = 'C:\Users\abcd3\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe'

$StorePass = 'esjzone123'
$KeyAlias = 'esjzone'
$KeyStore = Join-Path $Root 'keystore\esjzone.jks'
$OutApk = Join-Path $Root 'ESJZone-Reader-1.0.apk'

$env:JAVA_HOME = $JavaHome
$env:PATH = (Join-Path $JavaHome 'bin') + ';' + $env:PATH

foreach ($tool in @($Java, $Javac, $Keytool, $Aapt2, $ZipAlign, $ApkSigner, $AndroidJar, $Jsoup,
        $Python)) {
    if (-not (Test-Path $tool)) { throw "missing build dependency: $tool" }
}

function Step($name) { Write-Host "==> $name" -ForegroundColor Cyan }
function Run([string]$exe, [string[]]$arguments) {
    # native tools write notes to stderr; under a strict preference that would abort the build
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $exe @arguments
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
    if ($code -ne 0) {
        throw "$([System.IO.Path]::GetFileName($exe)) failed with exit code $code"
    }
}

if (Test-Path $Build) { Remove-Item $Build -Recurse -Force }
New-Item -ItemType Directory -Force -Path $Build, "$Build\gen", "$Build\classes", "$Build\dex" |
    Out-Null

Get-ChildItem $Root -Recurse -Force -Filter '.keep' -ErrorAction SilentlyContinue | Remove-Item -Force

# Layouts are only inflated on a device, so a missing layout_width in a style would sail
# through the build and kill the activity at setContentView. Catch it here instead.
Step 'lint resources (inflation safety)'
Run $Python @("$Root\tools\lint_resources.py")

Step 'aapt2 compile (resources)'
Run $Aapt2 @('compile', '--dir', "$Root\res", '-o', "$Build\res.zip")

Step 'aapt2 link (manifest, assets, resources, R.java)'
Run $Aapt2 @('link', '-o', "$Build\base.apk", '-I', $AndroidJar,
    '--manifest', "$Root\AndroidManifest.xml", '-A', "$Root\assets",
    '--java', "$Build\gen", "$Build\res.zip")

Step 'javac (sources + generated R.java)'
$sources = Get-ChildItem "$Root\src", "$Build\gen" -Recurse -Filter *.java |
    ForEach-Object { '"' + $_.FullName.Replace('\', '/') + '"' }
if ($sources.Count -eq 0) { throw 'no java sources found' }
# without a BOM: javac would treat one as part of the first argument
[System.IO.File]::WriteAllLines("$Build\sources.txt", $sources,
    (New-Object System.Text.UTF8Encoding($false)))
Run $Javac @('-J-Duser.language=en', '-J-Duser.country=US', '-nowarn',
    '-encoding', 'UTF-8', '-source', '8', '-target', '8', '-Xlint:-options',
    '-bootclasspath', $AndroidJar, '-cp', "$AndroidJar;$Jsoup", '-d', "$Build\classes",
    "@$Build\sources.txt")

Step 'jar (class files for d8)'
Run $Python @("$Root\tools\zipjar.py", "$Build\classes", "$Build\classes.jar")

Step 'd8 (app + jsoup -> classes.dex)'
Run $Java @('-cp', "$Bt\lib\d8.jar", 'com.android.tools.r8.D8', '--release',
    '--min-api', '21', '--lib', $AndroidJar, '--output', "$Build\dex",
    "$Build\classes.jar", $Jsoup)

Step 'pack APK (resources + classes.dex)'
Run $Python @("$Root\tools\pack_apk.py", "$Build\base.apk", "$Build\dex\classes.dex",
    "$Build\unsigned.apk")

Step 'zipalign'
Run $ZipAlign @('-f', '-p', '4', "$Build\unsigned.apk", "$Build\aligned.apk")

if (-not (Test-Path $KeyStore)) {
    Step 'keytool (creating the signing key)'
    New-Item -ItemType Directory -Force -Path (Split-Path $KeyStore) | Out-Null
    Run $Keytool @('-genkeypair', '-keystore', $KeyStore, '-alias', $KeyAlias,
        '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10950',
        '-storepass', $StorePass, '-keypass', $StorePass,
        '-dname', 'CN=ESJZone Reader, OU=Reader, O=ESJZone, L=Tokyo, C=JP')
}

Step 'apksigner'
Run $ApkSigner @('sign', '--ks', $KeyStore, '--ks-key-alias', $KeyAlias,
    '--ks-pass', "pass:$StorePass", '--key-pass', "pass:$StorePass",
    '--v1-signing-enabled', 'true', '--v2-signing-enabled', 'true', '--v3-signing-enabled', 'true',
    '--v4-signing-enabled', 'false',
    '--out', $OutApk, "$Build\aligned.apk")
Remove-Item "$OutApk.idsig" -Force -ErrorAction SilentlyContinue

Step 'verify'
Run $ApkSigner @('verify', '--print-certs', $OutApk)
Run $Aapt2 @('dump', 'badging', $OutApk)

$info = Get-Item $OutApk
Write-Host ''
Write-Host ("APK: {0}  ({1:N0} bytes)" -f $info.FullName, $info.Length) -ForegroundColor Green
