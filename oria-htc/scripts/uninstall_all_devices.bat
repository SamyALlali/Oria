@echo off
setlocal enabledelayedexpansion

set "PACKAGE_NAME=com.htc.vive.eagle.hackathon.starter"

if not defined ANDROID_HOME (
  if exist "%LOCALAPPDATA%\Android\Sdk" (
    set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
  )
)

set "ADB=adb"
if defined ANDROID_HOME (
  if exist "%ANDROID_HOME%\platform-tools\adb.exe" (
    set "ADB=%ANDROID_HOME%\platform-tools\adb.exe"
  )
)

echo Connected adb devices:
"%ADB%" devices
echo.

set /a COUNT=0
for /f "skip=1 tokens=1,2" %%A in ('"%ADB%" devices') do (
  if "%%B"=="device" (
    set /a COUNT+=1
    echo Uninstalling from %%A
    "%ADB%" -s %%A uninstall %PACKAGE_NAME%
    echo.
  )
)

if %COUNT% equ 0 (
  echo No authorized adb devices found.
  exit /b 2
)

echo Uninstall attempted on %COUNT% device(s).

