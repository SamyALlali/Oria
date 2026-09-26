@echo off
setlocal

set "PROJECT_DIR=%~dp0..\android-project"
set "APK_OUT=%~dp0..\apk\VIVE_Eagle_Hackathon_Starter_debug.apk"

if not defined JAVA_HOME (
  if exist "C:\Program Files\Android\Android Studio\jbr\bin\java.exe" (
    set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
  )
)

if defined JAVA_HOME (
  set "PATH=%JAVA_HOME%\bin;%PATH%"
)

if not defined ANDROID_HOME (
  if exist "%LOCALAPPDATA%\Android\Sdk" (
    set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
  )
)

cd /d "%PROJECT_DIR%" || exit /b 1
call gradlew.bat :app:assembleDebug || exit /b 1

copy /Y "%PROJECT_DIR%\app\build\outputs\apk\debug\app-debug.apk" "%APK_OUT%" >nul || exit /b 1
echo Built debug APK:
echo %APK_OUT%

