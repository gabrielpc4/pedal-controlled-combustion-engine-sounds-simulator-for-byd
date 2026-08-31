# BYD Audio Pack Installer

This APK is a small, signature-authorized USB bridge. It does not keep the sound library in its
own storage. It discovers `.bydpack` archives on removable storage, opens one archive at a time as
a read-only file descriptor, and sends that descriptor to the main app. The main app validates the
catalog identity, ZIP layout, hashes, WAV metadata, and size limits before atomically publishing the
pack under its private `files` directory.

## Device flow

1. Install the current main app and this installer APK, signed with the same certificate.
2. Connect the USB drive containing the generated `.bydpack` files.
3. Use `RESCAN USB`. Android 25 devices can expose readable `/storage/<volume>` roots directly.
4. If direct access is unavailable, including on scoped-storage Android 33 devices, use
   `CHOOSE USB FOLDER` and select the drive through Android's Storage Access Framework.
5. Use `INSTALL ALL`. The installer asks the main app for its current catalog and opens only files
   needed by missing or updated catalog identities.
6. Wait for final verification. `READY · N/N exact audio packs` is the only success state for the
   complete library. `INCOMPLETE` lists missing, stale, and extra identities.

Progress is stage-local and always displays the current stage. Failures are logged as
`FAILED <source> · <stage> · <error code> · <detail>`, so a receive, archive, manifest, catalog,
layout, extraction, WAV verification, commit, cleanup, or final-verification failure can be
distinguished without a file manager.

## Retry, updates, and cancellation

- Already exact packs are skipped without opening or copying them.
- If a USB drive contains multiple case-insensitive filename matches for one missing pack, each is
  retained as a candidate. An unreadable or corrupt first copy does not hide a later valid copy.
  Once the expected identity is installed, remaining copies for that identity are skipped.
- A failed or incomplete run can use `INSTALL ALL` again. The fresh main-app inventory is
  authoritative, so successfully committed packs are skipped and only remaining identities retry.
- A newer catalog identity is accepted by the main app and atomically replaces or coexists with
  the previous ready version according to current catalog retention. `CLEAN OBSOLETE` is available
  only after the batch is closed.
- `CANCEL` stops the active private-storage transaction. A cancel requested while the USB provider
  is opening a file cannot fall through to the next candidate. The previously installed ready pack
  remains unchanged.
- The Activity keeps the signed service connection while the document picker or a transient
  background transition is visible. Destruction cancels and unbinds; late scanner or Binder UI
  callbacks are discarded.

The installer never loads a whole pack into Java memory. Scan breadth, directory depth, file count,
and candidate count are bounded. The main app separately enforces archive, ZIP/ZIP64, member,
expanded-size, hash, path, WAV, capacity, crash-recovery, and transactional-publication limits.

## Focused verification

```shell
./gradlew :audio-installer:testDebugUnitTest :audio-installer:assembleDebug
./gradlew :audio-installer:connectedDebugAndroidTest
```

The connected suite covers the Android 33 automotive emulator's SAF document traversal, bounded
scans, Parcelable inventory, signature permission, Binder descriptor transfer, progress stages,
and rejection before commit.
