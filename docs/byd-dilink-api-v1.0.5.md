# BYD DiLink API V1.0.5 - English Engineering Notes

Source: `source-material/BYD_DiLink_API_V1.0.5.pdf`

- Original title: `比亚迪智慧开放平台 API 说明书`
- Document version: V1.0.5
- Document date: 2018-07-25
- Physical page count: 159
- SDK baseline stated by BYD: Android 7.1.2

## Purpose and interpretation rules

This is an engineering extraction and concise English translation of the actionable content in BYD's Chinese manual. It preserves API identifier spelling, including BYD's original typos, because the runtime API is case- and spelling-sensitive.

This manual predates the target 2025 Seal firmware by years. It proves that an API existed and documents its intended semantics; it does not prove that every class/method is present or permission-accessible on the Seal. BYD explicitly says the shipped SDK and vehicle configuration are authoritative.

The introduction says the platform opens 18 data categories, while the table of contents and API definitions enumerate 20 categories. This document follows the 20 defined sections.

## Revision history

### V1.0.0 - 2018-04-20

- First release.

### V1.0.2 - 2018-05-24

- `getDoorState(int area)` removed fuel-door input.
- `getWindowState(int area)` removed sunroof input.
- Added `getWindowOpenPercent(int area)` and `onWindowOpenPercent(int area, int value)`.
- Removed `getChargingMode()` / `onChargingModeChanged(int)`.
- Removed `getChargingState()` / `onChargingStateChanged(int)`.
- Removed instrument-theme get/set APIs.
- Removed fuel AD value getter/callback.
- Removed right-camera-switch getter/callback.
- Added forced-EV result to `getEnergyMode()`.
- Added reverse-right-front and reverse display modes to `getDisplayMode()`.

### V1.0.5 - 2018-07-25

- Removed backlight brightness/mode, acceleration-sensor, rear-control, auto-lock, date-format, daylight-saving-state, and panorama-setting APIs.
- Removed the OK/READY return from the `getPowerLevel()` getter. Note that the bodywork listener table still lists OK/READY as a possible callback, an internal document inconsistency.
- Added EV mileage, multimedia, ambient sensor, feature/self-learning interfaces for rear AC/image/PM2.5/sunshade, external charging energy, total electricity consumption, and vehicle model name.
- Added camera access and multimedia permission guidance.

The removed “acceleration” item in this revision appears to refer to a sensor API, not the still-documented accelerator-pedal `getAccelerateDeepness()` method.

## Global programming model

Each device family normally exposes:

1. a singleton-like `getInstance(Context)` factory;
2. synchronous `getXxx()` calls;
3. optional `setXxx()`/command calls;
4. an `AbsBYDAuto...Listener` with change callbacks;
5. `registerListener(listener)` and `unregisterListener(listener)`.

Generic flow:

```java
BYDAutoAcDevice device = BYDAutoAcDevice.getInstance(context);
device.registerListener(listener);
int result = device.start(BYDAutoAcDevice.AC_CTRL_SOURCE_VOICE);
device.unregisterListener(listener);
```

Important rules from the manual:

- Declare the appropriate manifest permissions.
- AC, bodywork, door-lock, instrument, panorama, and vehicle-setting families also require their `*_COMMON` permission to be requested dynamically before creating the device instance.
- `*_GET` permits reads; `*_SET` permits commands where that family supports commands.
- A setter's return value means that command delivery succeeded or failed; it does not prove the vehicle changed state. Confirm via a listener callback.
- Vehicle configuration and power state determine API availability.
- BYD recommends vehicle-control setters only while the vehicle is in ON power state.
- Actual SDK behavior is authoritative when it conflicts with the document.
- GPS and volume should use standard Android APIs.
- The manual says the APK needs a system signature to install/run.
- Standard Android Camera APIs expose configured cameras: camera ID 0 is the exterior/dashcam camera and ID 1 is the interior/dome camera.
- Media control additionally requires `com.byd.mediacenter.STARTSERVER`, documented as `signatureOrSystem`.

Common command-result families use symbolic outcomes equivalent to success, invalid input, timeout, busy, and failure. Numeric values should come from the runtime SDK rather than being duplicated from decompiled sources.

## Permission family map

The manual explains the pattern but does not repeat every permission beside every class. The following names are corroborated by sample manifests/decompiled SDKs:

| API family | Read/common/set permissions |
| --- | --- |
| Bodywork | `BYDAUTO_BODYWORK_COMMON`, `BYDAUTO_BODYWORK_GET` |
| Statistics | `BYDAUTO_STATISTIC_GET` |
| Speed/pedals | `BYDAUTO_SPEED_GET` |
| Energy/mode | `BYDAUTO_ENERGY_GET` |
| Panorama | `BYDAUTO_PANORAMA_COMMON`, `BYDAUTO_PANORAMA_GET` |
| AC | `BYDAUTO_AC_COMMON`, `BYDAUTO_AC_GET`, `BYDAUTO_AC_SET` |
| PM2.5 | `BYDAUTO_PM2P5_GET` |
| Engine | `BYDAUTO_ENGINE_GET` |
| Gearbox/brake | `BYDAUTO_GEARBOX_GET` |
| Door lock | `BYDAUTO_DOOR_LOCK_COMMON`, `BYDAUTO_DOOR_LOCK_GET` |
| Lights | `BYDAUTO_LIGHT_GET` |
| Safety belts | `BYDAUTO_SAFETY_BELT_GET` |
| Radar | `BYDAUTO_RADAR_GET` |
| Charging | `BYDAUTO_CHARGING_COMMON`, `BYDAUTO_CHARGING_GET` |
| Tyres | `BYDAUTO_TYRE_GET` |
| Instrument | `BYDAUTO_INSTRUMENT_COMMON`, `BYDAUTO_INSTRUMENT_GET`, `BYDAUTO_INSTRUMENT_SET` |
| Time | `BYDAUTO_TIME_GET` in known manifests/SDKs; verify write authorization for setters |
| Settings | `BYDAUTO_SETTING_COMMON`, `BYDAUTO_SETTING_GET`, `BYDAUTO_SETTING_SET` |
| Sensors | `BYDAUTO_SENSOR_GET` |
| Multimedia | `BYDAUTO_MULTIMEDIA_GET`, plus `com.byd.mediacenter.STARTSERVER` for `controlMedia` |

Permission names and protection levels must be queried on the target firmware.

## Steering-wheel media keys

The manual exposes three Android key codes for multimedia integration:

- `KEYCODE_MEDIA_PREVIOUS`
- `KEYCODE_MEDIA_NEXT`
- `KEYCODE_AUTO_MEDIA_VOICE`

## Complete API catalog

### 6.1 Bodywork - `BYDAutoBodyworkDevice`

Methods:

- `getInstance(Context)`
- `String getAutoVIN()` - 17-character VIN.
- `int getAutoModelName()` - old model enum; documented 2018 Song/Qin/Tang variants and `AUTO_MODEL_NULL`. Do not expect a Seal enum in this old document.
- `int getAutoSystemState()` - normal, security-set, security-started, undefined.
- `int getDoorState(int area)` - left/right front/rear doors, hood, luggage door; open/closed/undefined/invalid input.
- `int getWindowState(int area)` - four side windows; open/closed/undefined/invalid input.
- `int getWindowOpenPercent(int area)` - sunroof or sunshade, 0-100 percent; 0 closed, 100 fully open.
- `int getBatteryVoltageLevel()` - normal, low, invalid.
- `int getPowerLevel()` - OFF `0x0`, ACC `0x1`, ON `0x2`; listener table also mentions OK/READY `0x3` despite the V1.0.5 revision note.
- `double getSteeringWheelValue(int type)` - angle or angular speed. Angle range -780.0 to +779.9 degrees; speed 0-1016 degrees/second.
- `int getFuelElecLowPower()` - normal, low fuel, low electrical energy, or both.
- `int getAlarmState()` - alarm/no alarm; note says the alarm occurs when the key is out of range and a door is open.
- `int getMoonRoofConfig()` - none, panoramic sunroof/sunshade, fixed panoramic shade, or anti-pinch small sunroof.
- listener registration/unregistration.

Listener: `AbsBYDAutoBodyworkListener`

- `onAutoSystemStateChanged(int)`
- `onBatteryVoltageLevelChanged(int)`
- `onDoorStateChanged(int area, int state)`
- `onWindowStateChanged(int area, int state)`
- `onWindowOpenPercent(int area, int value)`
- `onPowerLevelChanged(int)`
- `onSteeringWheelValueChanged(int type, double value)`
- `onFuelElecLowPowerChanged(int)`
- `onAlarmStateChanged(int)`

### 6.2 Driving statistics - `BYDAutoStatisticDevice`

Methods and documented units/ranges:

- `double getDrivingTimeValue()` - 0-9999.9 hours.
- `int getElecDrivingRangeValue()` - 0-511 km.
- `double getElecPercentageValue()` - 0-100 percent.
- `int getFuelDrivingRangeValue()` - 0-4095 km.
- `int getFuelPercentageValue()` - 0-100 percent.
- `double getLastElecConPHMValue()` - recent electric consumption, -99.9 to 99.9 kWh/100 km.
- `double getLastFuelConPHMValue()` - recent fuel consumption, 0-51.1 L/100 km.
- `double getTotalElecConPHMValue()` - accumulated average electric consumption, -99.9 to 99.9 kWh/100 km.
- `double getTotalFuelConPHMValue()` - accumulated average fuel consumption, 0-51.1 L/100 km.
- `double getTotalFuelConValue()` - total fuel consumption, 0-104857.4 L.
- `double getTotalElecConValue()` - total electric energy, -1000 to 1676721.4 kWh. Battery discharge is positive; downhill/braking/engine generation can make energy received by the battery negative.
- `int getTotalMileageValue()` - 0-999999 km.
- `int getKeyBatteryLevel()` - low or normal.
- `int getEVMileageValue()` - 0-999999 km.
- listener registration/unregistration.

Electricity-related APIs apply to hybrid/EV vehicles.

Listener: `AbsBYDAutoStatisticListener`

- corresponding `on...Changed` callbacks for every metric above.
- The PDF prints `ontKeyBatteryLevelChanged`; decompiled later stubs use `onKeyBatteryLevelChanged`, so treat the PDF spelling as a document typo and compile against the actual SDK.

### 6.3 Speed and pedal depth - `BYDAutoSpeedDevice`

This is the primary project API.

- `int getAccelerateDeepness()` - accelerator depth, 0-100 percent.
- `int getBrakeDeepness()` - brake depth, 0-100 percent.
- `double getCurrentSpeed()` - 0-282.0 km/h.
- listener registration/unregistration.

Listener: `AbsBYDAutoSpeedListener`

- `onAccelerateDeepnessChanged(int value)`
- `onBrakeDeepnessChanged(int value)`
- `onSpeedChanged(double value)`

The identifier uses `Accelerate`, `Deepness`, and the constant spelling `DEEP_PERSENT_*`; preserve these spellings.

### 6.4 Energy and modes - `BYDAutoEnergyDevice`

Documented as applicable to hybrid vehicles.

- `getEnergyMode()` - stopped, EV, fuel, forced EV, HEV; fuel mode was marked unsupported.
- `getOperationMode()` - economy or sport.
- `getPowerGenerationState()` - invalid, generating, finished, unable to enter.
- `getPowerGenerationValue()` - stationary pedal-generation power, 1-31 kW.
- `getRoadSurfaceMode()` - keep-alive, normal, snow/gravel/grass, mud/ruts, sand.
- listener registration/unregistration.

Listener callbacks:

- `onEnergyModeChanged(int)`
- `onOperationModeChanged(int)`
- `onRoadSurfaceChanged(int)`
- `onPowerGenerationStateChanged(int)`
- `onPowerGenerationValueChanged(int)`

### 6.5 Panorama/cameras - `BYDAutoPanoramaDevice`

- `getPanoOutputSignal()` - CVBS or LVDS.
- `getPanoWorkState()` - off/working.
- `getBackLineConfig()` - unsupported, panorama-internal reverse guides, or multimedia reverse guides.
- `getPanoOutputState()` - off; front/rear/left/right; composite; matching; front+left/right; rear+left/right. Combined views have orientation/configuration limitations.
- `getPanoRotation()` - horizontal/vertical.
- `getDisplayMode()` - panorama, full screen, widget, reverse-right-front, reverse.
- `getPanoramaOnlineState()` - reverse+right-front, full panorama, reverse-only, or offline/no image configuration.
- listener registration/unregistration.

Listener callbacks:

- `onPanoOutputStateChanged(int)`
- `onPanoWorkStateChanged(int)`
- `onBackLineConfigChanged(int)`
- `onPanoRotationChanged(int)`
- `onDisplayModeChanged(int)`

The manual separately says raw camera images should be accessed through Android Camera APIs, not these status methods.

### 6.6 Air conditioning - `BYDAutoAcDevice`

Getters:

- `getAcCompressorMode()` - compressor on/off.
- `getAcCompressorManualSign()` - automatic/manual.
- `getAcWindLevelManualSign()` - automatic/manual.
- `getAcWindModeManualSign()` - automatic/manual.
- `getAcStartState()` - AC on/off.
- `getAcControlMode()` - manual/automatic.
- `getAcCycleMode()` - outside/recirculation.
- `getAcWindMode()` - face, face+feet, feet, feet+defrost, defrost, face+feet+defrost, face+defrost.
- `getAcDefrostState(int area)` - front/rear defrost on/off; rear depends on equipment.
- `getAcWindLevel()` - levels 0-7.
- `getTemprature(int area)` - spelling preserved; driver, passenger, rear, or outside temperature.
- `getTemperatureUnit()` - Fahrenheit/Celsius.
- `getAcTemperatureControlMode()` - linked/separate driver/passenger zones.
- `getAcVentilationState()` - parked ventilation on/off.
- `getRearAcStartState()` - rear AC on/off when configured.

Temperature ranges:

- cabin Celsius: 17-33 C;
- cabin Fahrenheit: 64-91 F;
- outside Celsius: -40 to 50 C;
- outside Fahrenheit: -40 to 122 F.

Commands:

- `setAcControlMode(int setSource, int mode)`
- `setAcCycleMode(int setSource, int mode)`
- `setAcWindMode(int setSource, int mode)`
- `setAcDefrostState(int setSource, int area, int state)`
- `setAcWindLevel(int setSource, int level)` - settable levels 1-7.
- `setAcTemperature(int type, int value, int tempSource, int unit)`
- `setAcTemperatureControlMode(int setSource, int mode)`
- `setAcVentilationState(int setSource, int state)`
- `start(int setSource)` / `stop(int setSource)`
- `startRearAc(int setSource)` / `stopRearAc(int setSource)`

Control source is voice or UI/key. Command outcomes are success, invalid, timeout, busy, failure.

Listener: `AbsBYDAutoAcListener`

- `onAcCompressorManualSignChanged(int)`
- `onAcCompressorModeChanged(int)`
- `onAcCtrlModeChanged(int)`
- `onAcCycleModeChanged(int)`
- `onAcDefrostStateChanged(int area, int state)`
- `onAcRearStarted()` / `onAcRearStoped()`
- `onAcStarted()` / `onAcStoped()`
- `onAcVentilationStateChanged(int)`
- `onAcWindLevelChanged(int)`
- `onAcWindLevelManualSignChanged(int)`
- `onAcWindModeChanged(int)`
- `onAcWindModeManualSignChanged(int)`
- `onAcWindModeShownStateChanged(int)`
- `onTemperatureChanged(int area, int value)`
- `onTemperatureUnitChanged(int)`

The API spells `Stoped` with one `p`.

### 6.7 Air quality - `BYDAutoPM2p5Device`

Applies to vehicles equipped with PM2.5 sensing.

- `getPM2p5OnlineState()` - module absent/offline or present/online; check in ON power state.
- `int[] getPM2p5CheckState()` - `[inside, outside]`, on/off.
- `int[] getPM2p5Level()` - `[inside, outside]`, invalid/excellent/good/light/moderate/heavy/severe, numeric levels 0-6.
- `int[] getPM2p5Value()` - `[inside, outside]`, 0-3000 micrograms/m3.
- listener registration/unregistration.

Listener callbacks:

- `onPM2p5CheckStateChanged(int inside, int outside)`
- `onPM2p5LevelChanged(int inside, int outside)`
- `onPM2p5ValueChanged(int inside, int outside)`

### 6.8 Engine/motor - `BYDAutoEngineDevice`

- `getEngineDisplacement()` - 0.0-25.5 L.
- `getEngineCode()` - old 2018 engine-code enum/string mapping.
- `getEnginePower()` - combined engine/motor power, -100 to 300 kW.
- `getEngineSpeed()` - 0-8000 r/min.
- `getEngineCoolantLevel()` - low/normal.
- `getOilLevel()` - 0-254.
- listener registration/unregistration.

Listener callbacks exist only for engine speed, coolant level, and oil level in V1.0.5.

For a pure-EV Seal, do not assume “engine speed” is a usable virtual RPM or motor RPM signal. Test for unsupported/constant/invalid behavior.

### 6.9 Gearbox and brake - `BYDAutoGearboxDevice`

- `getGearboxCode()` - legacy transmission model mapping.
- `getGearboxType()` - MT, AMT, AT, CVT, DCT.
- `getGearboxAutoModeType()` - P/R/N/D/S/M.
- `getGearboxManualModeLevel()` - D/R/N; document says only R and N were returned then, D reserved.
- `getBrakeFluidLevel()` - low/normal.
- `getParkBrakeSwitch()` - parking brake valid/invalid.
- `getBrakePedalState()` - pressed/not pressed.
- listener registration/unregistration.

Listener callbacks mirror all state getters except gearbox code/type.

This family's binary brake-pedal state can be a useful sanity check for `BYDAutoSpeedDevice.getBrakeDeepness()`, but requires `BYDAUTO_GEARBOX_GET` and should not be added until needed.

The manual constant names use the misspellings `BREAK` and `PADAL` in some places.

### 6.10 Door locks - `BYDAutoDoorLockDevice`

- `getDoorLockStatus(int area)` - left/right front/rear, rear hatch, left/right child lock; invalid/unlocked/locked.
- listener registration/unregistration.
- listener: `onDoorLockStatusChanged(int area, int state)`.

### 6.11 Lights - `BYDAutoLightDevice`

- `getLightAutoStatus()` - auto light switch on/off.
- `getLightStatus(int type)` - side, low beam, high beam, left/right turn, front/rear fog, mirror/foot light.
- `getAFSSwitch()` - adaptive front-lighting on/off.
- listener registration/unregistration.

Listener callbacks:

- `onLightAutoSwitchOff()` / `onLightAutoSwitchOn()`
- `onLightOff(int type)` / `onLightOn(int type)`
- `onAFSSwitchStateChange(int state)`

### 6.12 Safety belts and occupancy - `BYDAutoSafetyBeltDevice`

- `getSafetyBeltStatus(int area)` - driver, passenger, left/right/center second row; invalid/unlatched/latched.
- `getPassengerStatus(int area)` - passenger and second-row seats; invalid/occupied/empty.
- listener registration/unregistration.

Listener callbacks:

- `onSafetyBeltStatusChanged(int area, int state)`
- `onPassengerStatusChanged(int area, int state)`

### 6.13 Parking radar - `BYDAutoRadarDevice`

- `getRadarProbeState(int area)` - left, left-front, front-left-center, left-rear, right, right-front, front-right-center, right-rear.
- `getAllRadarProbeStates()` - array order: left-front, right-front, left-rear, right-rear, left, right, front-left-center, front-right-center.
- Probe states: invalid, sensor abnormal, safe, green, yellow, red.
- `getReverseRadarSwitchState()` - on/off.
- listener registration/unregistration.

Listener callbacks:

- `onRadarProbeStateChanged(int area, int state)`
- `onReverseRadarSwitchStateChanged(int state)`

### 6.14 Charging - `BYDAutoChargingDevice`

- `getChargerFaultState()` - normal/minor/major fault.
- `getChargerWorkState()` - ready/start/finish/terminated.
- `getChargingCapacity()` - energy accumulated in current session; getter table says 0-65.534 kWh, while the listener table says 0-131.07 kWh. Treat this as a document inconsistency and use the runtime SDK/vehicle.
- `getChargingType()` - default, AC, VTOG, GB DC, GB non-DC.
- `int[] getChargingRestTime()` - `[hours, minutes]`, hours 0-254, minutes 0-59.
- `getChargingCapState(int type)` - AC/DC charge-port door open/closed; DC applies to EVs.
- `getChargingPortLockRebackState()` - charge-port lock feedback. The English meaning of LOCK/UNLOCK constants appears reversed in the Chinese table; verify on the vehicle.
- `getDischargeRequestState()` - none, household device, three-phase equipment/vehicle, grid, in-car socket, single-phase vehicle.
- `getChargerState()` - connected/not connected.
- `getChargingGunState()` - none, AC, DC, both AC+DC, VTOL discharge gun.
- `getChargingPower()` - 0-500 kW.
- `getBatteryManagementDeviceState()` - ready, charging, finished, discharging, terminated, multiple charger/gun/external-equipment faults, schedule waiting, vehicle discharge, cabinet timeout, discharge finished.
- `getChargingScheduleEnableState()` - enabled/disabled.
- `getChargingScheduleState()` - invalid, canceled, none, local, remote.
- `getChargingGunNotInsertedState()` - remind/do not remind.
- `int[] getChargingScheduleTime()` - `[hours, minutes]`, 0-23 / 0-59.
- listener registration/unregistration.

`AbsBYDAutoChargingListener` provides a corresponding change callback for every state/value above.

### 6.15 Tyres/TPMS - `BYDAutoTyreDevice`

Areas are left-front, right-front, left-rear, right-rear.

- `getTyreAirLeakState(int area)` - normal, quick leak, slow leak, invalid.
- `getTyreBatteryState()` - normal/low.
- `getTyrePressureState(int area)` - normal/overpressure/underpressure/invalid.
- `getTyrePressureValue(int area)` - 0-4094 kPa.
- `getTyreSignalState(int area)` - normal/error/invalid.
- `getTyreSystemState()` - normal, self-checking, signal anomaly, fault, masked. The manual says the controller may report masked while the vehicle is stationary.
- `getTyreTemperatureState()` - normal, super-high, high, display sleep.
- listener registration/unregistration.

Listener callbacks mirror every getter, with `area` for per-wheel values.

### 6.16 Instrument cluster - `BYDAutoInstrumentDevice`

- `getMalfunctionInfo(int typeName)` - tests a large set of warning/indicator types including oil pressure, parking brake, charging system, engine, ABS, ESP, rapid leak, high coolant temperature, EPB, SRS, EPS, tyre pressure, SVS, high motor temperature, battery, high battery temperature, power system, OK, EV, HEV, smart key, and front belt. Result is present/absent.
- `getAlarmBuzzleState()` - buzzer sounding/stopped; spelling preserved.
- `getUnit(int unitName)` - temperature, pressure, fuel-consumption/distance, power unit.
- `getMaintenanceInfo(int typeName)` - time 0-720 days or mileage 0-20000 km.
- `setUnit(int unitName, int unitValue)`.
- `setMaintenanceInfo(int typeName, int infoValue)`.
- `getExternalChargingPower()` - cumulative energy obtained from external charging, 0.0-10000.0 kWh.
- listener registration/unregistration.

Unit choices:

- temperature: C/F;
- pressure: bar/psi/kPa;
- consumption/distance: L/100 km + km, km/L + km, GB mpg + mile, US mpg + mile, kWh/100 km + km, kWh/100 mi + mile; the document says only choices 1, 3, and 4 were supported then;
- power: kW/HP.

Listener callbacks:

- `onMalfunctionInfoChanged(int typeName, int hasMalfunction)`
- `onAlarmBuzzleStateChange(int state)`
- `onMaintenanceInfoChanged(int typeName, int infoValue)`
- `onExternalChargingPowerChanged(double value)`

### 6.17 Time - `BYDAutoTimeDevice`

- `int[] getTime()` - `[year, month, day, hour, minute, second]`; year 2001-2255, month 1-12, day 1-31, hour 0-23, minute/second 0-59.
- `getTimeFormat()` - 12/24 hour.
- `setDate(int year, int month, int day, int weekday)`.
- `setTime(int hour, int minute, int second)`.
- `setTimeFormat(int value)`.
- listener registration/unregistration.

Listener callbacks:

- `onTimeChanged(int[] time)`
- `onTimeFormatChanged(int value)`

### 6.18 Vehicle settings - `BYDAutoSettingDevice`

The settings API uses paired getters/setters. `SET_ON`/`SET_OFF` are common. Setter outcomes: success, failure, invalid input, timeout, busy. A getter result spelled `SET_INVAID` means the setting does not exist on that vehicle.

AC/air settings:

- `get/setACBTWind` - reduce fan during Bluetooth call; default on.
- `get/setACTunnelCycle` - automatic recirculation in tunnels; default on.
- `get/setACPauseCycle` - automatic recirculation while parked; default off.
- `get/setACAutoAir` - economy/comfort auto-AC mode; default comfort.
- `get/setPM25Power` - PM2.5 check at power-on; default on.
- `get/setPM25SwitchCheck` - PM2.5 check on door state; default off.
- `get/setPM25TimeCheck` - 30-minute PM2.5 timed check; default off.

Energy/high-voltage settings:

- `get/setEnergyFeedback` - standard or large regeneration/energy-feedback strength; default standard.
- `get/setSOCTarget` - target SOC 15-70 percent; documented default 25 percent.

Charging/media/general settings:

- `get/setChargingPort` - charge-port anti-theft lock enabled/disabled; default enabled.
- `get/setAutoExternalRearMirrorFollowUpSwitch` - mirror fold/follow behavior; default on.
- `get/setLockOff` - unlock all four doors or driver only; default all.
- `get/setLanguage` - simplified/traditional Chinese, English, Russian, Arabic; setter note says only simplified Chinese supported in that version.
- `get/setOverspeedLock` - speed-triggered locking; default on.
- `getSafeWarnState` - warning/no-warning for conditions such as “switch to P.”
- `getMaintainRemindState` - maintenance reminder state.

Steering/auxiliary settings:

- `get/setSteerAssis` - comfort/sport steering assistance.
- `get/setRearViewMirrorFlip` - reverse mirror tilt; default on.
- `get/setDriverSeatAutoReturn` - driver-seat auto-return; default on.
- `get/setSteerPositionAutoReturn` - steering-position auto-return; default on.
- `get/setRemoteControlUpwindowState` - remote window raise; default on, absent without anti-pinch.
- `get/setRemoteControlDownwindowState` - remote window lower; default off.
- `get/setLockCarRiseWindow` - close windows on lock; default off, absent without anti-pinch.
- `get/setMicroSwitchLockWindowState` - long-press micro-switch lock/raise windows; default on, absent without anti-pinch.
- `get/setMicroSwitchUnlockWindowState` - long-press micro-switch unlock/lower windows; default off.
- `get/setBackHomeLightDelayValue` - follow-me-home delay: 0 or 10-60 seconds; default 10.
- `get/setLeftHomeLightDelayValue` - leave-home delay: 0 or 10-60 seconds; default 10.
- `get/setBackDoorElectricMode` - powered/manual rear hatch.

Feature/configuration methods:

- `hasFeature(String feature)` - documented feature strings cover overspeed locking, powered rear door, and mirror follow-up; result present/absent.
- `getRearAcOnlineState()` - rear AC module online/offline, checked in ON power.
- listener registration/unregistration.

`AbsBYDAutoSettingListener` contains corresponding callbacks for all changeable settings, including AC/PM2.5, energy feedback/SOC, charging-port lock, mirror follow, unlock mode, language, overspeed lock, safety/maintenance warning, steering assist, mirror tilt, seat/steering return, remote/micro-switch windows, home-light delays, rear-AC online, and rear-hatch mode.

These are vehicle-control APIs. They are out of scope for the read-only motor-sound POC.

### 6.19 Ambient sensor - `BYDAutoSensorDevice`

- `getLightIntensity()` - ambient light level:
  - level 1: >230 lux;
  - level 2: >180 and <230 lux;
  - level 3: >130 and <180 lux;
  - level 4: >80 and <130 lux;
  - level 5: <80 lux.
- listener registration/unregistration.
- listener: `onLightIntensityChanged(int value)`.

Boundary equality behavior is not specified by the table.

### 6.20 Multimedia - `BYDAutoMultimediaDevice`

This controls the built-in BYD media center, including audio/video, radio, and Bluetooth music.

Getters:

- `getMediaType()` - AM, FM, CD, VCD, DVD, TV, audio off, AUX, USB audio/video, SD audio/video, hard-disk audio/video, local audio/video, Bluetooth, robot, invalid. Invalid is returned when the media center is not running/was removed from recent tasks. Available types vary by vehicle.
- `getPlayMode()` - single repeat, random, preview, scan, radio stereo, all repeat, invalid.
- `getPlayState()` - play/pause/stop. The Chinese descriptions for pause/stop labels appear transposed in one table; use runtime behavior.
- `getPlayMediaInfo()` - `MediaInfo` with `fileName`, `artistName`, `albumName`; null when the media center is not running or another app owns audio focus. FM returns empty info; music returns available name/artist/album; video returns name only.

Control:

- `controlMedia(int mode, int action, MediaControlParam param)`.
- Modes: radio, music, video.
- Actions: enter/open, play, pause, previous, next, set play pattern, radio auto-search, cancel radio search.
- Parameters are key/value pairs: source (local/USB/SD/Bluetooth), pattern (cycle/random/single/half-screen/full-screen), radio frequency, filename, artist.
- Result: success/failure.

Listener: `AbsBYDAutoMultimediaListener`

- `onMediaTypeChanged(int type)`
- `onPlayModeChanged(int mode)`
- `onPlayStateChanged(int state)`
- `onPlayMediaInfoChanged(MediaInfo mediaInfo)`

When media type changes cause mode/state/info changes, all associated callbacks may also fire.

## Implications specifically for BYD Motor Sound

1. Use only section 6.3 for the first probe.
2. Consider section 6.9 binary brake state only as a later cross-check.
3. Use callbacks rather than repeated getters when possible.
4. The V1.0.5 permission/signing notes make unsigned access the critical unknown.
5. The old SDK baseline means method presence is plausible on legacy-compatible DiLink, but the target Seal's implementation may expose later extensions, omit old modules, or remap signals.
6. Do not import unrelated control permissions or setters from the manual.
7. Preserve exact API spellings when creating compile-only signatures.

## Known document inconsistencies/typos

- Says 18 categories but defines 20.
- `getPowerLevel()` revision removes OK/READY while listener table retains it.
- Charging-capacity maximum differs between getter and callback tables.
- Some lock/unlock feedback descriptions appear reversed.
- `getTemprature`, `getAccelerateDeepness`, `DEEP_PERSENT`, `onAcStoped`, `getAlarmBuzzleState`, `BREAK_PADAL`, and `SET_INVAID` are misspelled identifiers/constant names in the API material.
- `ontKeyBatteryLevelChanged` appears in the PDF; later SDK stubs use `onKeyBatteryLevelChanged`.
- Some range tables use the `MIN` constant name twice where the second should be `MAX`.
- Some play-state Chinese labels for pause/stop appear swapped.

When any discrepancy matters, inspect the actual SDK JAR for the target build and validate behavior on the car.
