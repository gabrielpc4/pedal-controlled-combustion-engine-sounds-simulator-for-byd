package com.gabrielpc.enginesoundsimulator.telemetry

import com.gabrielpc.enginesoundsimulator.drive.InputMode
import kotlin.math.roundToInt

data class BydAvailabilityReport(
    val vehiclePedalsAvailable: Boolean,
    val bydLiveWouldShowUnavailable: Boolean,
    val summary: String,
    val blockers: List<String>,
    val hints: List<String>,
)

fun TelemetrySnapshot.vehiclePedalsAvailable(): Boolean =
    readerState == ReaderState.ACTIVE && accelerator.isValid && brake.isValid

fun buildBydAvailabilityReport(
    mode: InputMode,
    telemetry: TelemetrySnapshot,
): BydAvailabilityReport {
    val available = telemetry.vehiclePedalsAvailable()
    val blockers = mutableListOf<String>()
    val hints = mutableListOf<String>()

    if (telemetry.readerState != ReaderState.ACTIVE) {
        blockers += "A leitura está em ${telemetry.readerState.name}; o esperado é ACTIVE"
        when (telemetry.readerState) {
            ReaderState.IDLE -> hints += "A leitura ainda não iniciou. Abra o app e espere um segundo."
            ReaderState.PROBING -> hints += "O app ainda está procurando as classes BYD. Aguarde um momento."
            ReaderState.ACTIVE -> Unit
            ReaderState.UNAVAILABLE -> {
                telemetry.lastError?.let { blockers += "Erro ao testar a conexão: $it" }
                hints += "O teste da API de velocidade BYD falhou. Veja os detalhes abaixo."
            }
            ReaderState.STOPPED -> hints += "A leitura foi interrompida. Volte para a tela principal do app."
        }
    }

    if (!telemetry.accelerator.isValid) {
        blockers += formatSignalBlocker("Acelerador", telemetry.accelerator)
    }
    if (!telemetry.brake.isValid) {
        blockers += formatSignalBlocker("Freio", telemetry.brake)
    }

    telemetry.lastError?.takeIf { telemetry.readerState == ReaderState.ACTIVE }?.let {
        blockers += "Erro na última leitura: $it"
    }

    addPermissionHints(telemetry, hints)
    addSignalHints(telemetry, hints)

    val bydLiveBlocked = mode == InputMode.VEHICLE && !available
    val summary = when {
        available && mode == InputMode.VEHICLE -> "A BYD ao vivo está recebendo dados válidos dos pedais."
        available -> "Os pedais BYD estão válidos, mas o modo ${mode.displayName} ainda pode usar o simulador."
        bydLiveBlocked -> "A BYD ao vivo está bloqueada: ${blockers.firstOrNull() ?: "motivo desconhecido"}"
        else -> "Os pedais BYD não estão disponíveis."
    }

    return BydAvailabilityReport(
        vehiclePedalsAvailable = available,
        bydLiveWouldShowUnavailable = bydLiveBlocked,
        summary = summary,
        blockers = blockers,
        hints = hints.distinct(),
    )
}

private fun formatSignalBlocker(label: String, signal: SignalValue): String {
    val raw = signal.raw?.let { "bruto=${formatTelemetryNumber(it)}" } ?: "bruto=—"
    val issue = signal.issue ?: "sem valor"
    return "$label inválido ($raw, $issue)"
}

private fun addPermissionHints(telemetry: TelemetrySnapshot, hints: MutableList<String>) {
    telemetry.diagnostics.forEach { line ->
        when {
            line.contains("BYDAUTO_SPEED_GET") && line.contains("denied") -> {
                hints += "BYDAUTO_SPEED_GET foi negada. A DiLink costuma liberar isso só para apps assinados pela BYD."
            }
            line.contains("BYDAUTO_SPEED_GET") && line.contains("not defined") -> {
                hints += "BYDAUTO_SPEED_GET não existe neste firmware. O aparelho ou a versão pode não ser compatível."
            }
            line.contains("BYDAUTO_SPEED_COMMON") && line.contains("denied") -> {
                hints += "BYDAUTO_SPEED_COMMON foi negada."
            }
            line.contains("Probe failure") && line.contains("ClassNotFoundException") -> {
                hints += "A classe de velocidade BYD não está disponível. Emulador e celular sempre falham — use a central do Seal."
            }
            line.contains("Probe failure") && line.contains("SecurityException") -> {
                hints += "SecurityException ao testar. A assinatura ou uma permissão bloqueou o acesso à API da BYD."
            }
        }
    }
}

private fun addSignalHints(telemetry: TelemetrySnapshot, hints: MutableList<String>) {
    listOf(
        telemetry.accelerator.issue to "acelerador",
        telemetry.brake.issue to "freio",
        telemetry.speed.issue to "velocidade",
    ).forEach { (issue, name) ->
        when (issue) {
            "permission denied" -> hints += "A leitura de $name retornou o aviso de permissão BYD negada."
            "SDK not available" -> hints += "A leitura de $name informou que o SDK não está disponível nesta versão."
            "feature unbound" -> hints += "A leitura de $name informou que o serviço ainda não está pronto."
            "no data" -> if (name == "velocidade") {
                hints += "A velocidade não trouxe dados. Os pedais ainda podem funcionar; a velocidade simulada será usada."
            }
        }
    }
}

fun formatTelemetryNumber(value: Double): String =
    if (value == value.roundToInt().toDouble()) {
        value.roundToInt().toString()
    } else {
        String.format("%.2f", value)
    }
