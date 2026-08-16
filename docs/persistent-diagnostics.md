# Persistent diagnostics

The debug APK keeps a small event trail that remains available after the dashboard closes, the
process crashes, or Android kills it. This is deliberately separate from a live `adb logcat`
session, which may not retain the useful part of a failure.

## What is recorded

The app creates an event when its process and dashboard lifecycle begin/end. The drivetrain
controller records input-source changes, control toggles, every shift start/completion, and a
one-second heartbeat containing gear, RPM, speed, throttle, brake, shift serial, and reader state.
It also records controller/loop failures, BYD telemetry probe failures with stack traces, and
telemetry read failure/recovery transitions. The common logger remains available for audio-route
changes and other low-rate state changes. It must not be called from the 200 Hz simulation loop or
audio-buffer writer on every iteration: log transitions and a bounded-rate heartbeat instead.

Each entry contains wall-clock and elapsed timestamps, a process/session ID, severity, event name,
and redacted developer-supplied details. Do not put IMEI, ICCID, VIN, location, ADB credentials, or
other vehicle identifiers into those details.

## Storage and retrieval

The active file is app-private and flushed/synced per event:

```text
/data/user/0/com.gabrielpc.enginesoundsimulator/files/diagnostics/drive-events.log
```

It rolls into `drive-events.previous.log` before the active file exceeds 256 KiB. The two-file
window bounds storage to roughly 512 KiB while preserving the immediately preceding interval.

With the debuggable APK installed, retrieve both files after reproducing a problem:

```powershell
adb shell run-as com.gabrielpc.enginesoundsimulator cat files/diagnostics/drive-events.log
adb shell run-as com.gabrielpc.enginesoundsimulator cat files/diagnostics/drive-events.previous.log
```

To save the active log on the host, use `adb exec-out` rather than relying on an open Logcat window:

```powershell
adb exec-out run-as com.gabrielpc.enginesoundsimulator cat files/diagnostics/drive-events.log > drive-events.log
```

To isolate the shift sequence after a reproduction in PowerShell:

```powershell
adb shell run-as com.gabrielpc.enginesoundsimulator cat files/diagnostics/drive-events.log |
    Select-String 'shift_started|shift_completed|drive_heartbeat'
```

`run-as` is available for the debug build. A production-signed non-debug build may deliberately
deny it; do not make these internal logs world-readable merely to work around that restriction.

## Interpreting a failure

For a gear issue, inspect the requested/committed gear, RPM, road speed, throttle, brake, and input
source recorded for each automatic shift. The transition sequence, not a dense trace of every
simulation step, should show whether a downshift was immediately followed by an unwanted upshift.
An uncaught exception is recorded before Android's normal crash handler runs.

## Scripted regression coverage

`DriveControllerScriptedIntegrationTest` drives the real controller directly on an emulator: it
holds simulated throttle until third gear completes, releases it without a UI gesture, then verifies
that the completed downshift remains in second gear. It reads the same persisted event trail after
stopping the controller and fails if an upward shift appears after the lift-off marker. This is the
preferred repeatable way to investigate shift behavior without touchscreen-input timing affecting
the result.
