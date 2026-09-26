# Troubleshooting

## No Device Appears

Run:

```bat
adb devices
```

If no phone appears:

- Confirm the USB cable supports data.
- Unlock the phone.
- Enable USB debugging.
- Accept the RSA debugging prompt on the phone.
- Reconnect the cable and run `adb devices` again.

## Device Shows As Unauthorized

Unlock the phone and accept the USB debugging prompt. If it does not appear, toggle USB debugging off and on, then reconnect USB.

## Install Fails With Signature Mismatch

Only uninstall this starter app package:

```bat
adb uninstall com.htc.vive.eagle.hackathon.starter
scripts\install_all_devices.bat
```

Do not factory reset the phone. Do not uninstall or modify VIVE Connect.

## Starter Stays Disconnected

Return to the starter, select `Eagle`, and tap `Connect` again. If the connection still fails, ask the hackathon organizer to check the prepared phone and glasses.

If logs show `ERROR_UNREGISTERED_APP`, confirm that this exact package is registered or whitelisted by HTC:

```text
com.htc.vive.eagle.hackathon.starter
```

## Confirm Package Name In APK

Use Android SDK build tools:

```bat
aapt dump badging apk\VIVE_Eagle_Hackathon_Starter_debug.apk
```

Expected package:

```text
com.htc.vive.eagle.hackathon.starter
```

Or run the full automated check:

```bat
scripts\check_package_name.bat
```

Expected final line:

```text
PACKAGE CHECK RESULT: PASS
```

## Gradle 8.13 Local ZIP

This project uses the Gradle Wrapper, but it is configured to use the included local ZIP:

```text
gradle-dist/gradle-8.13-bin.zip
```

The wrapper setting is:

```properties
distributionUrl=../../../gradle-dist/gradle-8.13-bin.zip
```

Keep `gradle-dist` next to `android-project` when copying the folder to another machine.

## UnknownHostException: services.gradle.org

With the default USB setup, Gradle should not need `services.gradle.org` for the Gradle 8.13 distribution. If Gradle still fails with `java.net.UnknownHostException: services.gradle.org`, check whether `android-project\gradle\wrapper\gradle-wrapper.properties` was changed back to an HTTPS URL or whether another Gradle/plugin dependency is being resolved online.

Try:

- Confirm `gradle-dist\gradle-8.13-bin.zip` exists.
- Confirm `distributionUrl=../../../gradle-dist/gradle-8.13-bin.zip`.
- Re-run `scripts\check_package_name.bat`.
- If someone intentionally switched back to an HTTPS Gradle URL, connect to a working internet connection or restore the local ZIP setting.

## Restore The Local Gradle ZIP

If the ZIP is missing:

1. Download:

```text
https://services.gradle.org/distributions/gradle-8.13-bin.zip
```

2. Put it here:

```text
gradle-dist/gradle-8.13-bin.zip
```

3. Run `scripts\check_package_name.bat`.

Do not change `applicationId`, `namespace`, package declarations, signing config, or add secrets.
