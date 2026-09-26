@echo off
setlocal enabledelayedexpansion

set "PACKAGE_NAME=com.htc.vive.eagle.hackathon.starter"
set "APK=%~dp0..\apk\VIVE_Eagle_Hackathon_Starter_debug.apk"

if not exist "%APK%" (
  echo APK not found: %APK%
  echo Run scripts\build_debug.bat first.
  exit /b 1
)

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
    echo Installing on %%A
    "%ADB%" -s %%A shell pm path %PACKAGE_NAME% >nul 2>&1
    if !errorlevel! equ 0 (
      echo Existing package found on %%A; attempting reinstall.
    ) else (
      echo Package not currently installed on %%A.
    )

    "%ADB%" -s %%A install -r -d "%APK%" > "%TEMP%\vive_eagle_install_%%A.txt" 2>&1
    type "%TEMP%\vive_eagle_install_%%A.txt"
    findstr /i "INSTALL_FAILED_UPDATE_INCOMPATIBLE INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES SIGNATURE" "%TEMP%\vive_eagle_install_%%A.txt" >nul
    if !errorlevel! equ 0 (
      echo Signature mismatch or incompatible existing install detected on %%A.
      echo Uninstalling %PACKAGE_NAME% from %%A and retrying.
      "%ADB%" -s %%A uninstall %PACKAGE_NAME%
      "%ADB%" -s %%A install "%APK%" || exit /b 1
    )
    del "%TEMP%\vive_eagle_install_%%A.txt" >nul 2>&1
    echo.
  )
)

if %COUNT% equ 0 (
  echo No authorized adb devices found.
  echo Enable USB debugging, accept the RSA prompt on the phone, then run this script again.
  exit /b 2
)

echo Installed on %COUNT% device(s).

