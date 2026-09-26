$ErrorActionPreference = "Stop"

$expectedPackage = "com.htc.vive.eagle.hackathon.starter"
$rootDir = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
$projectDir = Join-Path $rootDir "android-project"
$apkPath = Join-Path $rootDir "apk\VIVE_Eagle_Hackathon_Starter_debug.apk"
$gradleFile = Join-Path $projectDir "app\build.gradle.kts"
$settingsFile = Join-Path $projectDir "settings.gradle.kts"
$srcDir = Join-Path $projectDir "app\src"
$docsDir = Join-Path $rootDir "docs"
$readmeFile = Join-Path $rootDir "README.md"
$scriptsDir = Join-Path $rootDir "scripts"
$result = $true

function Write-Section {
    param([string] $Title)
    Write-Host ""
    Write-Host "== $Title =="
}

function Write-Pass {
    param([string] $Message)
    Write-Host "PASS: $Message" -ForegroundColor Green
}

function Write-Fail {
    param([string] $Message)
    $script:result = $false
    Write-Host "FAIL: $Message" -ForegroundColor Red
}

function Get-CheckFiles {
    param([string[]] $Paths)

    foreach ($path in $Paths) {
        if (-not (Test-Path -LiteralPath $path)) {
            continue
        }

        $item = Get-Item -LiteralPath $path
        if (-not $item.PSIsContainer) {
            $item
            continue
        }

        Get-ChildItem -LiteralPath $path -Recurse -File -ErrorAction SilentlyContinue |
            Where-Object {
                $_.FullName -notmatch "\\app\\build\\" -and
                $_.FullName -notmatch "\\\.gradle\\" -and
                $_.FullName -notmatch "\\repository\\"
            }
    }
}

function Show-Matches {
    param(
        [string] $Title,
        [string] $Pattern,
        [string[]] $Paths
    )

    Write-Section $Title
    $matches = Get-CheckFiles $Paths | Select-String -Pattern $Pattern -ErrorAction SilentlyContinue
    if ($matches) {
        $matches | ForEach-Object {
            Write-Host "$($_.Path):$($_.LineNumber):$($_.Line.Trim())"
        }
    } else {
        Write-Host "No matches."
    }
}

Write-Host "VIVE Eagle Hackathon Starter package check"
Write-Host "Expected package: $expectedPackage"
Write-Host "Project: $projectDir"

Show-Matches "Gradle applicationId / namespace" "applicationId|namespace" @($gradleFile, $settingsFile)
Show-Matches "AndroidManifest files" "package=|android:name|applicationId" @($srcDir)
Show-Matches "Kotlin/Java package declarations" "^\s*package\s+" @($srcDir)
Show-Matches "All com.htc references in project, scripts, and docs" "com\.htc" @($projectDir, $docsDir, $scriptsDir, $readmeFile)

Write-Section "Static validation"

if (-not (Test-Path -LiteralPath $gradleFile)) {
    Write-Fail "Missing app Gradle file: $gradleFile"
} else {
    $gradleText = Get-Content -LiteralPath $gradleFile -Raw
    if ($gradleText -match ('applicationId\s*=\s*"' + [regex]::Escape($expectedPackage) + '"')) {
        Write-Pass "applicationId is $expectedPackage"
    } else {
        Write-Fail "applicationId is not $expectedPackage"
    }

    if ($gradleText -match ('namespace\s*=\s*"' + [regex]::Escape($expectedPackage) + '"')) {
        Write-Pass "namespace is $expectedPackage"
    } else {
        Write-Fail "namespace is not $expectedPackage"
    }
}

$oldSampleMatches = Get-CheckFiles @($rootDir) |
    Where-Object { $_.FullName -ne $PSCommandPath } |
    Select-String -Pattern "com\.htc\.viveglass\.viveglasssample|ViveGlassSample" -ErrorAction SilentlyContinue
if ($oldSampleMatches) {
    Write-Fail "Old sample package/name references remain outside generated/vendor folders:"
    $oldSampleMatches | ForEach-Object {
        Write-Host "$($_.Path):$($_.LineNumber):$($_.Line.Trim())"
    }
} else {
    Write-Pass "No old ViveGlassSample package/name references found outside generated/vendor folders"
}

$badPackages = Get-ChildItem -LiteralPath $srcDir -Recurse -Include *.kt,*.java -File -ErrorAction SilentlyContinue |
    Select-String -Pattern "^\s*package\s+([A-Za-z0-9_.]+)" |
    Where-Object {
        $packageName = $_.Matches[0].Groups[1].Value
        $packageName -ne $expectedPackage -and -not $packageName.StartsWith("$expectedPackage.")
    }
if ($badPackages) {
    Write-Fail "Unexpected Kotlin/Java package declarations:"
    $badPackages | ForEach-Object {
        Write-Host "$($_.Path):$($_.LineNumber):$($_.Line.Trim())"
    }
} else {
    Write-Pass "All Kotlin/Java package declarations are under the expected package"
}

Write-Section "Build debug APK"
$buildScript = Join-Path $scriptsDir "build_debug.bat"
& $buildScript
if ($LASTEXITCODE -ne 0) {
    Write-Fail "Debug APK build failed"
} else {
    Write-Pass "Debug APK build completed"
}

Write-Section "APK package validation"
$sdkRoots = @()
if ($env:ANDROID_HOME) { $sdkRoots += $env:ANDROID_HOME }
if ($env:ANDROID_SDK_ROOT) { $sdkRoots += $env:ANDROID_SDK_ROOT }
$defaultSdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
if (Test-Path -LiteralPath $defaultSdk) { $sdkRoots += $defaultSdk }

$aapt = $null
foreach ($sdkRoot in ($sdkRoots | Select-Object -Unique)) {
    $buildTools = Join-Path $sdkRoot "build-tools"
    if (-not (Test-Path -LiteralPath $buildTools)) {
        continue
    }

    $aapt = Get-ChildItem -LiteralPath $buildTools -Recurse -Filter "aapt.exe" -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending |
        Select-Object -First 1 -ExpandProperty FullName
    if ($aapt) {
        break
    }
}

if (-not $aapt) {
    Write-Fail "Could not find aapt.exe in Android SDK build-tools"
} elseif (-not (Test-Path -LiteralPath $apkPath)) {
    Write-Fail "APK not found: $apkPath"
} else {
    $badging = & $aapt dump badging $apkPath
    $packageLine = $badging | Where-Object { $_ -like "package:*" } | Select-Object -First 1
    $apkPackage = $null
    if ($packageLine -match "name='([^']+)'") {
        $apkPackage = $Matches[1]
    }

    if ($apkPackage -eq $expectedPackage) {
        Write-Pass "APK package is $apkPackage"
    } else {
        Write-Fail "APK package is '$apkPackage' but expected '$expectedPackage'"
    }
}

Write-Host ""
Write-Host "========================================"
if ($result) {
    Write-Host "PACKAGE CHECK RESULT: PASS" -ForegroundColor Green
    Write-Host "========================================"
    exit 0
}

Write-Host "PACKAGE CHECK RESULT: FAIL" -ForegroundColor Red
Write-Host "========================================"
exit 1
