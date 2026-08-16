# Electro Car App APK - Relevant Static Analysis

Last analyzed: 2026-08-15

Artifact: `../reference/Electro-Car-App.apk` (excluded from Git)

This analysis answers one narrow question: why a third-party-signed APK can work on the same head unit even though this project's normal app process is denied `BYDAUTO_SPEED_GET`.

## Identity and signer

| Field | Value |
| --- | --- |
| Package | `br.com.rory.electro` |
| Version | `1.12.0` (`versionCode 180`) |
| Minimum / target API | 25 / 25 |
| SHA-256 | `17B419B5C81F1235F895757D36E8D8A269CDF1DBC7F67AD079045DA1F5E2CD32` |
| Signer subject | `CN=Rory Kinape, OU=Electro, O=Electro Co, L=Curitiba, ST=Paraná, C=55` |
| Signer SHA-256 | `428f92f723f3aa7b18a9a26d1f0eedc61a95fdb7efed4f23e79bdc6bbaef895e` |

The certificate is not the BYD signer used by the original motor-sound APK.

## Manifest evidence

Electro requests no `BYDAUTO_*` permission, including no `BYDAUTO_SPEED_GET`. It therefore cannot demonstrate that a normal third-party package receives that signature permission.

Its manifest instead requests powerful Android permissions including `WRITE_SECURE_SETTINGS`, `READ_LOGS`, `SYSTEM_ALERT_WINDOW`, and `REQUEST_INSTALL_PACKAGES`, and exposes an explicit setup/bootstrap surface that includes:

- `DevToolsActivity`
- `EnableUSBDebugActivity`
- `ADBAuthActivity`
- `EnableAutoStartActivity`
- an accessibility service named `SecondaryService`
- a persistent main service, boot receiver, and restart job

The APK also packages ARM64 native libraries named `libelectrolib.so`, `libelectropkg.so`, `libnative-lib.so`, and `libspake2.so`.

## What can and cannot be concluded

**Observed:** Electro is third-party signed, installs on the target car, and contains a deliberate ADB/debugging authorization workflow.

**Inference:** privileged work may be bootstrapped through ADB/shell or another service rather than performed under the ordinary application UID. Static inspection alone does not prove the exact runtime telemetry path.

**Not supported:** Electro is not evidence that declaring `BYDAUTO_SPEED_GET`, requesting it at runtime, or calling `pm grant` will grant the signature permission to another normal APK.

Do not copy Electro's broad permission set or accessibility automation into this project. The current implementation first uses the much narrower property of BYD's SDK: its speed getter checks the caller-supplied context. A shell helper remains a fallback only if firmware `2503` also enforces the caller UID remotely.

## Reproducible local inspection

Android SDK Build Tools were used to inspect the user-supplied file without modifying it:

```powershell
aapt2 dump badging reference/Electro-Car-App.apk
aapt2 dump permissions reference/Electro-Car-App.apk
aapt2 dump xmltree reference/Electro-Car-App.apk --file AndroidManifest.xml
java -jar <android-sdk>/build-tools/36.0.0/lib/apksigner.jar verify --print-certs reference/Electro-Car-App.apk
apkanalyzer manifest print reference/Electro-Car-App.apk
apkanalyzer files list reference/Electro-Car-App.apk
```
