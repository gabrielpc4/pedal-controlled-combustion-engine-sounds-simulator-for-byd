package com.gabrielpc.enginesoundsimulator.telemetry

fun TelemetrySnapshot.vehiclePedalsAvailable(): Boolean =
    readerState == ReaderState.ACTIVE && accelerator.isValid && brake.isValid
