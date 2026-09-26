# Student Setup Guide

## Goal

Use this Android starter project to begin coding VIVE Eagle smart glasses experiences quickly during the hackathon.

## Requirements

- Android Studio installed.
- HTC Android phone prepared by the organizer.
- USB debugging enabled on the phone.
- USB cable that supports data transfer.

## Open And Run

1. Open Android Studio.
2. Open the `android-project` folder.
3. Wait for Gradle sync.
4. Connect the HTC phone.
5. On the phone, accept the USB debugging RSA prompt if shown.
6. In Android Studio, choose the connected phone.
7. Press `Run`.
8. In the starter app, select `Eagle` and tap `Connect`.

## Rebuild From Command Line

From the USB folder:

```bat
scripts\check_package_name.bat
scripts\build_debug.bat
scripts\install_all_devices.bat
```

The Gradle Wrapper is configured to use the included local Gradle ZIP:

```text
gradle-dist/gradle-8.13-bin.zip
```

As long as the full USB folder is copied, students should not need to download Gradle from the internet.

## Package Name Rule

Keep this package name unchanged:

```text
com.htc.vive.eagle.hackathon.starter
```

Changing it can break HTC SDK / VIVE Connect whitelisting and may prevent the app from connecting correctly.

Before handing the project to another team, run:

```bat
scripts\check_package_name.bat
```

Expected final result:

```text
PACKAGE CHECK RESULT: PASS
```

## Check Installation

```bat
adb devices
adb shell pm path com.htc.vive.eagle.hackathon.starter
```

If the second command prints a package path, the app is installed.
