# Organizer Prep Report

## Final App Identity

- App name: VIVE Eagle Hackathon Starter
- Package name: com.htc.vive.eagle.hackathon.starter
- Target platform: Android
- Build type: Debug
- Development stage: APK / Test build ready

## Paths

- Android project: `android-project`
- Debug APK: `apk/VIVE_Eagle_Hackathon_Starter_debug.apk`
- Source SDK inspected: `C:\Users\vived\Documents\MARTIN-BOISSE\Project\EAGLE\SDK\V_0.6.0\Android_ViveGlassSDK_0.6.0_beta`

## Commands

Build:

```bat
scripts\build_debug.bat
```

Package consistency check:

```bat
scripts\check_package_name.bat
```

Install on all connected adb devices:

```bat
scripts\install_all_devices.bat
```

Optional uninstall:

```bat
scripts\uninstall_all_devices.bat
```

## Build Verification

- Gradle task run: `:app:assembleDebug`
- Result: successful
- APK package verified with `aapt dump badging`
- Verified package: `com.htc.vive.eagle.hackathon.starter`
- Gradle Wrapper distribution: local `gradle-dist/gradle-8.13-bin.zip`
- Gradle Wrapper URL: `../../../gradle-dist/gradle-8.13-bin.zip`
- First build Gradle download required: no, if the full USB folder is copied
- Verified label: `VIVE Eagle Hackathon Starter`

## Device Installation

Detected adb devices during preparation:

```text
adb-CN46V3M00284-HQyxMK._adb-tls-connect._tcp device product:enodugls_02401 model:HTC_U24_pro device:htc_enodugls
adb-CN46V3M00298-ZGwfzk._adb-tls-connect._tcp device product:enodugls_02401 model:HTC_U24_pro device:htc_enodugls
adb-CN4B53M00860-AKbbY4._adb-tls-connect._tcp device product:enodugls_02401 model:HTC_U24_pro device:htc_enodugls
```

Install result:

```text
Installed successfully on 3 HTC U24 Pro devices.
Latest deployment completed on September 18, 2026.
Verified package path on each device for com.htc.vive.eagle.hackathon.starter.
```

Public VIVE Connect validation on pilot device `CN46V3M00284`:

```text
VIVE Connect package: com.htc.glassesca
VIVE Connect version tested: 1.4.1387452
VIVE AI Glasses SDK: 0.6.0
Starter connection result: CONNECTED
SDK authentication and BLE handshake: successful
```

## Assumptions

- Public VIVE Connect is installed from Google Play on each phone.
- VIVE Eagle glasses pairing is left untouched.
- The official sample's local Maven repository and bundled AAR are included in `android-project`.
- No secrets, tokens, passwords, Google account passwords, or keystores were added.

## Manual Steps Still Needed

- Add VIVE Eagle serial numbers for HTC Taiwan.
- Update public VIVE Connect and the VIVE Eagle firmware on each event phone.
- Confirm VIVE Connect pairing and SDK authentication during final event rehearsal.

## HTC Taiwan Whitelisting Info

- Project / App Name: VIVE Eagle Hackathon Starter
- Package Name Android: com.htc.vive.eagle.hackathon.starter
- Target Platform: Android
- Development Stage: APK / Test build ready
- Purpose of SDK Integration: Provide a ready-to-use SDK starter app for developer teams during a two-day hackathon, allowing them to build and test VIVE Eagle smart glasses experiences immediately using preconfigured Android phones.
- Google Play Store Account Emails:
  - silmohackathonteam1@gmail.com
  - silmohackathonteam2@gmail.com
  - silmohackathonteam3@gmail.com
- VIVE Eagle Serial Numbers: to be added manually by organizer
