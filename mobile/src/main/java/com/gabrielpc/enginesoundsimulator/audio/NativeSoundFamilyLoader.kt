package com.gabrielpc.enginesoundsimulator.audio

import com.gabrielpc.enginesoundsimulator.catalog.CurvePointV1
import com.gabrielpc.enginesoundsimulator.catalog.CarEngineMetadata
import com.gabrielpc.enginesoundsimulator.catalog.EngineGasAssistMetadata
import com.gabrielpc.enginesoundsimulator.catalog.InstalledSoundFamily
import com.gabrielpc.enginesoundsimulator.catalog.PackTrackRole
import com.gabrielpc.enginesoundsimulator.catalog.PackTrackPitchMode
import com.gabrielpc.enginesoundsimulator.catalog.PackOneShotGateControl
import com.gabrielpc.enginesoundsimulator.catalog.PackOneShotGroupNodeV2
import com.gabrielpc.enginesoundsimulator.catalog.PackOneShotNodeV2
import com.gabrielpc.enginesoundsimulator.catalog.PackOneShotPolicyKind
import com.gabrielpc.enginesoundsimulator.catalog.PackOneShotPlayMode
import com.gabrielpc.enginesoundsimulator.catalog.PackOneShotProgramV2
import com.gabrielpc.enginesoundsimulator.catalog.PackOneShotSelectionMode
import com.gabrielpc.enginesoundsimulator.catalog.PackOneShotSilentNodeV2
import com.gabrielpc.enginesoundsimulator.catalog.PackOneShotTrackNodeV2
import com.gabrielpc.enginesoundsimulator.catalog.PackOneShotTrigger
import com.gabrielpc.enginesoundsimulator.catalog.PackOneShotTriggerPolicyV2
import com.gabrielpc.enginesoundsimulator.catalog.PackEngineEventArmingMode
import com.gabrielpc.enginesoundsimulator.catalog.PackEngineTransientReentryPolicy
import com.gabrielpc.enginesoundsimulator.catalog.PackLimiterProgramMode
import com.gabrielpc.enginesoundsimulator.catalog.PackLimiterEventPolicyV2
import com.gabrielpc.enginesoundsimulator.catalog.PackTurboEventProgramMode
import com.gabrielpc.enginesoundsimulator.catalog.PackZeroGainTransitionPitch
import com.gabrielpc.enginesoundsimulator.catalog.PackZeroGainTransitionPhaseTreatment
import com.gabrielpc.enginesoundsimulator.catalog.PackZeroGainTransitionPolicy
import com.gabrielpc.enginesoundsimulator.catalog.PackZeroGainVirtualizationKind
import com.gabrielpc.enginesoundsimulator.catalog.SoundTrackManifestV1
import com.gabrielpc.enginesoundsimulator.catalog.TurboControllerMetadata
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.InterruptedIOException
import java.nio.ShortBuffer
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

internal class PreparedNativeSoundProfile(
    val familyId: String,
    val carId: String,
    val profile: EngineSampleProfile,
    val renderer: SampleEngineRenderer,
    private val clips: List<NativePcm16Clip>,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val retirementQueued = AtomicBoolean(false)
    @Volatile var retireNext: PreparedNativeSoundProfile? = null
    val decodedBytes: Long = clips.fold(0L) { total, clip -> Math.addExact(total, clip.decodedBytes) }

    fun markRetirementQueued(): Boolean = retirementQueued.compareAndSet(false, true)

    fun closeOnce(): Boolean {
        if (!closed.compareAndSet(false, true)) return false
        renderer.closeNativeMixer()
        clips.forEach(NativePcm16Clip::close)
        return true
    }

    override fun close() {
        closeOnce()
    }
}

internal object NativeSoundFamilyLoader {
    fun decode(
        family: InstalledSoundFamily,
        carId: String,
        budget: DecodedAudioBudget,
        cancellation: NativeDecodeCancellation,
    ): PreparedNativeSoundProfile {
        val manifest = family.manifest
        val car = requireNotNull(manifest.car(carId)) { "Sound family does not contain car $carId" }
        validateContinuousCurves(manifest.tracks)
        validateDecodedBudget(manifest.totalDecodedBytes, budget)

        val clips = ArrayList<NativePcm16Clip>(manifest.tracks.size)
        val clipsByPath = LinkedHashMap<String, NativePcm16Clip>()
        val decoded = LinkedHashMap<String, PlanarPcmData>(manifest.tracks.size)
        try {
            manifest.tracks.forEach { track ->
                checkCancellation(cancellation)
                val clip = clipsByPath[track.path] ?: run {
                    val file = family.trackFile(track)
                    require(fileSha256(file, cancellation) == track.flacSha256) {
                        "FLAC hash changed after import: ${track.id}"
                    }
                    // The manifest reservation is exact. Passing the declared physical-track
                    // size prevents corrupt STREAMINFO from allocating outside the reservation.
                    val uniqueClip = NativeFlacDecoder.decode(file, track.decodedBytes, cancellation)
                    clips += uniqueClip
                    clipsByPath[track.path] = uniqueClip
                    checkCancellation(cancellation)
                    require(uniqueClip.channelCount == 2) { "Track ${track.id} is not stereo" }
                    require(uniqueClip.frameCount == track.frameCount) {
                        "Track ${track.id} decoded ${uniqueClip.frameCount} frames; " +
                            "manifest expected ${track.frameCount}"
                    }
                    val identityData = NativePlanarPcmData(
                        clip = uniqueClip,
                        loopStartFrame = 0,
                        loopEndFrameExclusive = uniqueClip.frameCount.toInt(),
                    )
                    require(pcmSha256(identityData, cancellation) == track.pcmSha256) {
                        "PCM hash mismatch for ${track.id}"
                    }
                    uniqueClip
                }
                val data = NativePlanarPcmData(
                    clip = clip,
                    loopStartFrame = track.loopStartFrame?.toInt() ?: 0,
                    loopEndFrameExclusive = track.loopEndFrameExclusive?.toInt() ?: clip.frameCount.toInt(),
                )
                decoded[track.id] = data
            }
            checkCancellation(cancellation)
            val profile = manifest.engineSampleProfileFor(
                carId,
                family.previewFile(carId)?.absolutePath,
            )
            val renderer = SampleEngineRenderer.fromDecoded(
                outputSampleRate = SoundFamilyManifestV1Audio.SAMPLE_RATE,
                decoded = decoded,
                profile = profile,
            )
            return PreparedNativeSoundProfile(manifest.familyId, carId, profile, renderer, clips)
        } catch (error: Throwable) {
            clips.forEach(NativePcm16Clip::close)
            throw error
        }
    }

    private fun checkCancellation(cancellation: NativeDecodeCancellation) {
        if (Thread.currentThread().isInterrupted || cancellation.isCancelled()) {
            throw InterruptedIOException("Sound-family decode cancelled")
        }
        cancellation.nativeHandle()
    }

    private fun fileSha256(file: File, cancellation: NativeDecodeCancellation): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).buffered().use { input ->
            val buffer = ByteArray(FILE_HASH_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                checkCancellation(cancellation)
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private fun pcmSha256(data: NativePlanarPcmData, cancellation: NativeDecodeCancellation): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val block = ByteArray(PCM_HASH_BLOCK_FRAMES * SoundTrackManifestV1.AUDIO_BYTES_PER_FRAME.toInt())
        var firstFrame = 0
        while (firstFrame < data.frameCount) {
            checkCancellation(cancellation)
            val count = minOf(PCM_HASH_BLOCK_FRAMES, data.frameCount - firstFrame)
            var byteIndex = 0
            var localFrame = 0
            while (localFrame < count) {
                val frame = firstFrame + localFrame
                val left = data.shortSample(0, frame).toInt()
                val right = data.shortSample(1, frame).toInt()
                block[byteIndex++] = left.toByte()
                block[byteIndex++] = (left ushr 8).toByte()
                block[byteIndex++] = right.toByte()
                block[byteIndex++] = (right ushr 8).toByte()
                localFrame += 1
            }
            digest.update(block, 0, byteIndex)
            firstFrame += count
        }
        return digest.digest().toHex()
    }

    /** Prevents a malformed/default curve from running every RPM voice at full range. */
    internal fun validateContinuousCurves(tracks: List<SoundTrackManifestV1>) {
        val continuous = tracks.filter { it.role in CONTINUOUS_ROLES }
        continuous.forEach { track ->
            require(track.rpmCurve.size >= 2) { "Continuous track ${track.id} needs an explicit RPM window" }
        }
        if (continuous.size >= 3) {
            val probes = continuous.flatMap { track -> track.rpmCurve.map(CurvePointV1::input) }.distinct()
            require(probes.none { rpm -> continuous.all { it.rpmCurve.amplitudeAt(rpm) > CURVE_ACTIVE_EPSILON } }) {
                "Continuous curves activate every RPM voice simultaneously"
            }
        }
    }

    /**
     * Profiles above the device soft budget are not decoded speculatively. The
     * offline compiler must first emit shorter, authored RPM-window captures;
     * the hard budget remains an unconditional safety limit.
     */
    internal fun validateDecodedBudget(totalDecodedBytes: Long, budget: DecodedAudioBudget) {
        require(totalDecodedBytes > 0L) { "Decoded family must contain PCM" }
        require(totalDecodedBytes <= budget.hardBytes) {
            "Decoded family requires $totalDecodedBytes bytes; hard budget is ${budget.hardBytes}"
        }
        require(totalDecodedBytes <= budget.softBytes) {
            "Decoded family requires $totalDecodedBytes bytes; soft budget is ${budget.softBytes}. " +
                "This pack requires compiler-defined RPM windows before it can be activated."
        }
    }

    private fun ByteArray.toHex(): String {
        val chars = CharArray(size * 2)
        var index = 0
        while (index < size) {
            val value = this[index].toInt() and 0xff
            chars[index * 2] = HEX[value ushr 4]
            chars[index * 2 + 1] = HEX[value and 0xf]
            index += 1
        }
        return String(chars)
    }

    private const val FILE_HASH_BUFFER_BYTES = 64 * 1024
    private const val PCM_HASH_BLOCK_FRAMES = 4_096
    private const val HEX = "0123456789abcdef"
    private const val CURVE_ACTIVE_EPSILON = 1e-4
    private val CONTINUOUS_ROLES = setOf(
        PackTrackRole.IDLE, PackTrackRole.COAST, PackTrackRole.TEXTURE,
        PackTrackRole.INTAKE, PackTrackRole.EXHAUST,
    )
}

private fun List<CurvePointV1>.amplitudeAt(input: Double): Double {
    if (input <= first().input) return first().output
    if (input >= last().input) return last().output
    var index = 1
    while (index < size) {
        val right = this[index]
        if (input <= right.input) {
            val left = this[index - 1]
            val fraction = (input - left.input) / (right.input - left.input)
            return left.output + (right.output - left.output) * fraction
        }
        index += 1
    }
    return last().output
}

internal class NativePlanarPcmData(
    internal val clip: NativePcm16Clip,
    override val loopStartFrame: Int,
    override val loopEndFrameExclusive: Int,
) : PlanarPcmData {
    private val channels: Array<ShortBuffer> = Array(clip.channelCount, clip::channel)

    override val sampleRate: Int get() = clip.sampleRate
    override val sourceChannels: Int get() = clip.channelCount
    override val frameCount: Int get() = clip.frameCount.toInt()

    init {
        require(loopStartFrame >= 0 && loopStartFrame < loopEndFrameExclusive)
        require(loopEndFrameExclusive <= frameCount)
    }

    fun shortSample(channel: Int, frame: Int): Short = channels[channel].get(frame)

    override fun normalizedSample(channel: Int, frame: Int): Double =
        shortSample(channel, frame).toDouble() / 32_768.0
}

internal fun com.gabrielpc.enginesoundsimulator.catalog.SoundFamilyManifestV1.engineSampleProfileFor(
    carId: String,
    previewPath: String? = null,
): EngineSampleProfile {
    val car = requireNotNull(car(carId))
    val layers = ArrayList<SampleLayerSpec>()
    val effects = ArrayList<SampleEffectSpec>()
    val limiterPoliciesByTrackId = buildMap<String, PackLimiterEventPolicyV2> {
        oneShotPrograms.forEach { program ->
            val limiterPolicy = program.limiterEventPolicy ?: return@forEach
            program.nodes.filterIsInstance<PackOneShotTrackNodeV2>().forEach { node ->
                put(node.trackId, limiterPolicy)
            }
        }
    }
    tracks.forEach { track ->
        when (track.role) {
            PackTrackRole.IDLE,
            PackTrackRole.COAST,
            PackTrackRole.TEXTURE,
            PackTrackRole.INTAKE,
            PackTrackRole.EXHAUST -> layers += track.toLayer(car.engine.tachometerMaximumRpm)

            PackTrackRole.TURBO,
            PackTrackRole.SPOOL,
            PackTrackRole.BOV,
            PackTrackRole.TURBO_TRANSIENT,
            PackTrackRole.TRANSMISSION,
            PackTrackRole.LIMITER,
            PackTrackRole.SHIFT_UP,
            PackTrackRole.SHIFT_DOWN,
            PackTrackRole.OVERRUN,
            PackTrackRole.POP,
            PackTrackRole.BANG,
            PackTrackRole.CRACK,
            PackTrackRole.ENGINE_TRANSIENT -> effects +=
                track.toEffect(limiterPoliciesByTrackId[track.id])
        }
    }
    val turboControllerBank = car.engine.toTurboControllerBank()
    val turboPhysics = car.engine.toTurboPhysicsSpec(turboControllerBank)
    return EngineSampleProfile(
        id = carId,
        displayName = car.displayName,
        assetDirectory = familyId,
        previewAssetName = previewPath.orEmpty(),
        outputSampleRate = SoundFamilyManifestV1Audio.SAMPLE_RATE,
        minimumRpm = 0.0,
        maximumRpm = car.engine.tachometerMaximumRpm,
        idleRpm = car.engine.idleRpm,
        redlineRpm = car.engine.redlineRpm,
        limiterRpm = car.engine.limiterRpm,
        upshiftRpm = car.gearbox.upshiftRpm,
        gearRatios = car.gearbox.forwardRatios,
        upshiftDurationSeconds = car.gearbox.upshiftTimeMs / 1_000.0,
        downshiftDurationSeconds = car.gearbox.downshiftTimeMs / 1_000.0,
        layers = layers,
        effects = effects,
        oneShotPrograms = oneShotPrograms.map { program ->
            program.toRuntimeProgram(
                car.oneShotTriggerPolicies[program.id], tracks.associateBy(SoundTrackManifestV1::id),
            )
        },
        limiterHz = car.engine.limiterHz,
        turboControllerBank = turboControllerBank,
        turboPhysics = turboPhysics,
        turboPhysicalThrottleCurve = requireNotNull(car.engine.throttleMap.points.toAutomationCurve()),
        engineGasAssist = car.gearbox.engineGasAssist.toRuntimeSpec(),
        authoredCarMetadata = authoredCarMetadata(car.id, car.engine, car.gearbox, turboControllerBank),
        throttleOutputGainDb = AutomationCurve(
            listOf(CurvePoint(0.0, familyAttenuationDb), CurvePoint(1.0, familyAttenuationDb)),
        ),
    )
}

private fun SoundTrackManifestV1.toLayer(maximumRpm: Double): SampleLayerSpec = SampleLayerSpec(
    id = id,
    assetName = id,
    role = when (role) {
        PackTrackRole.IDLE -> SampleLayerRole.IDLE
        PackTrackRole.COAST -> SampleLayerRole.COAST
        PackTrackRole.TEXTURE -> SampleLayerRole.TEXTURE
        PackTrackRole.INTAKE -> SampleLayerRole.INTAKE
        PackTrackRole.EXHAUST -> SampleLayerRole.EXHAUST
        else -> error("${role.name} is not a continuous engine layer")
    },
    startRpm = 0.0,
    endRpm = maximumRpm,
    autopitchRootRpm = rootRpm,
    authoredRelativeRateCurve = if (
        pitchMode == PackTrackPitchMode.AUTHORED_PROPERTY_ONE_RELATIVE_RATE
    ) {
        requireNotNull(pitchCurve.toAutomationCurve())
    } else {
        null
    },
    baseGainDb = gainDb,
    throttleAmplitudeCurve = gainCurve.toAutomationCurve(),
    rpmAmplitudeCurves = rpmCurve.toAutomationCurve()?.let(::listOf).orEmpty(),
    softwareVoicePriority = softwareChannelPriority,
)

private fun PackOneShotProgramV2.toRuntimeProgram(
    policy: PackOneShotTriggerPolicyV2?,
    tracksById: Map<String, SoundTrackManifestV1>,
): OneShotProgramSpec = OneShotProgramSpec(
    id = id,
    trigger = trigger.toRuntimeTrigger(),
    rootNodeIds = rootNodeIds,
    nodes = nodes.map(PackOneShotNodeV2::toRuntimeNode),
    policy = if (trigger == PackOneShotTrigger.ENGINE_EVENT) {
        val enginePolicy = requireNotNull(engineEventPolicy)
        require(policy == null) { "ENGINE_EVENT must not have a per-car effect policy" }
        OneShotTriggerPolicySpec(
            kind = OneShotPolicyKind.ENGINE_EVENT_REGION,
            minimumRpm = 0.0,
            maximumRpm = null,
            armPedal = null,
            firePedal = null,
            armBoost = null,
            initialPeakPedal = null,
            initialArmPedal = null,
            initialFirePedal = null,
            minimumArmSeconds = 0.0,
            cooldownSeconds = 0.0,
            periodHz = null,
            engineEvent = EngineEventProgramPolicySpec(
                requiresEventStartInside =
                    enginePolicy.armingMode == PackEngineEventArmingMode.EVENT_START_INSIDE_REQUIRED,
                parameterGates = enginePolicy.parameterRegions
                    .flatMap { it.parameterGates }
                    .map { it.toRuntimeGate() },
                laneCount = enginePolicy.laneCount,
                maximumDecodedOneShotFrames = enginePolicy.maximumDecodedOneShotFrames,
                logicalVoiceLimit = enginePolicy.logicalVoiceLimit,
                softwareRealVoiceBudget = enginePolicy.softwareRealVoiceBudget,
            ),
        )
    } else if (trigger == PackOneShotTrigger.LIMITER_EVENT) {
        val effectPolicy = requireNotNull(policy) { "Missing trigger policy for $id" }
        require(effectPolicy.kind == PackOneShotPolicyKind.LIMITER_EVENT)
        val limiterPolicy = requireNotNull(limiterEventPolicy)
        val trackId = nodes.filterIsInstance<PackOneShotTrackNodeV2>().single().trackId
        val decodedFrames = tracksById.getValue(trackId).frameCount
        val laneCount = if (
            limiterPolicy.programMode == PackLimiterProgramMode.PERSISTENT_DECAY_REGION_ONE_SHOT
        ) {
            minOf(2_048L, (decodedFrames + 479L) / 480L).toInt()
        } else {
            0
        }
        OneShotTriggerPolicySpec(
            kind = OneShotPolicyKind.PERSISTENT_LIMITER_EVENT,
            minimumRpm = effectPolicy.minimumRpm,
            maximumRpm = null,
            armPedal = null,
            firePedal = null,
            armBoost = null,
            initialPeakPedal = null,
            initialArmPedal = null,
            initialFirePedal = null,
            minimumArmSeconds = 0.0,
            cooldownSeconds = 0.0,
            periodHz = null,
            limiterEvent = PersistentLimiterProgramPolicySpec(
                mode = when (limiterPolicy.programMode) {
                    PackLimiterProgramMode.PERSISTENT_TIMELINE_PERIODIC_ONE_SHOT ->
                        PersistentLimiterProgramMode.TIMELINE_PERIOD_LOOP
                    PackLimiterProgramMode.PERSISTENT_DECAY_REGION_ONE_SHOT ->
                        PersistentLimiterProgramMode.DECAY_REGION_ONE_SHOT
                    PackLimiterProgramMode.PERSISTENT_DECAY_REGION_LOOP ->
                        PersistentLimiterProgramMode.DECAY_REGION_LOOP
                },
                decayGainCurve = requireNotNull(limiterPolicy.decayGainCurve.toAutomationCurve()),
                decayPlacement = limiterPolicy.decayPlacement?.let { placement ->
                    LimiterDecayPlacementSpec(
                        placement.minimumSeconds, placement.maximumSeconds,
                        placement.includeMinimum, placement.includeMaximum,
                    )
                },
                timelinePeriodFrames = limiterPolicy.timelinePlacement?.periodFramesAt48k,
                oneShotLaneCount = laneCount,
            ),
        )
    } else if (trigger == PackOneShotTrigger.TURBO_EVENT) {
        require(policy == null) { "TURBO_EVENT must not have a per-car effect policy" }
        val turboPolicy = requireNotNull(turboEventPolicy)
        OneShotTriggerPolicySpec(
            kind = OneShotPolicyKind.TURBO_EVENT_PROGRAM,
            minimumRpm = 0.0,
            maximumRpm = null,
            armPedal = null,
            firePedal = null,
            armBoost = null,
            initialPeakPedal = null,
            initialArmPedal = null,
            initialFirePedal = null,
            minimumArmSeconds = 0.0,
            cooldownSeconds = 0.0,
            periodHz = null,
            turboEvent = TurboEventProgramPolicySpec(
                mode = when (turboPolicy.programMode) {
                    PackTurboEventProgramMode.BOOST_RELEASE_REGION_ONE_SHOT ->
                        TurboEventProgramMode.BOOST_RELEASE_REGION_ONE_SHOT
                    PackTurboEventProgramMode.TIMELINE_PERIODIC_ONE_SHOT ->
                        TurboEventProgramMode.TIMELINE_PERIODIC_ONE_SHOT
                    PackTurboEventProgramMode.PARAMETER_SHEET_EVENT_START_ONE_SHOT ->
                        TurboEventProgramMode.PARAMETER_SHEET_EVENT_START_ONE_SHOT
                },
                placementMinimumBoost = turboPolicy.placementMinimumBoost,
                placementMaximumBoost = turboPolicy.placementMaximumBoost,
                includeMinimum = turboPolicy.includeMinimum,
                includeMaximum = turboPolicy.includeMaximum,
                timelineStartFrames = turboPolicy.timelineStartFrames,
                timelinePeriodFrames = turboPolicy.timelinePeriodFrames,
                coreProgram = turboPolicy.coreProgram,
            ),
        )
    } else {
        val effectPolicy = requireNotNull(policy) { "Missing trigger policy for $id" }
        OneShotTriggerPolicySpec(
        kind = when (effectPolicy.kind) {
            PackOneShotPolicyKind.AC_BACKFIRE -> OneShotPolicyKind.AC_BACKFIRE
            PackOneShotPolicyKind.BOV_LIFT -> OneShotPolicyKind.BOV_LIFT
            PackOneShotPolicyKind.LIMITER -> OneShotPolicyKind.LIMITER
            PackOneShotPolicyKind.LIMITER_EVENT -> OneShotPolicyKind.PERSISTENT_LIMITER_EVENT
            PackOneShotPolicyKind.SHIFT_UP -> OneShotPolicyKind.SHIFT_UP
            PackOneShotPolicyKind.SHIFT_DOWN -> OneShotPolicyKind.SHIFT_DOWN
        },
        minimumRpm = effectPolicy.minimumRpm,
        maximumRpm = effectPolicy.maximumRpm,
        armPedal = effectPolicy.armPedal,
        firePedal = effectPolicy.firePedal,
        armBoost = effectPolicy.armBoost,
        initialPeakPedal = effectPolicy.initialPeakPedal,
        initialArmPedal = effectPolicy.initialArmPedal,
        initialFirePedal = effectPolicy.initialFirePedal,
        minimumArmSeconds = effectPolicy.minimumArmMs / 1_000.0,
        cooldownSeconds = effectPolicy.cooldownMs / 1_000.0,
        periodHz = effectPolicy.periodHz,
        )
    },
    softwareVoicePriority = softwareChannelPriority,
)

private fun com.gabrielpc.enginesoundsimulator.catalog.PackOneShotParameterGateV2.toRuntimeGate() =
    OneShotParameterGateSpec(
        control = when (control) {
            PackOneShotGateControl.ENGINE_RPM -> OneShotGateControl.ENGINE_RPM
            PackOneShotGateControl.ACCELERATOR -> OneShotGateControl.ACCELERATOR
            PackOneShotGateControl.SHIFT_STATE -> OneShotGateControl.SHIFT_STATE
            PackOneShotGateControl.BOOST -> OneShotGateControl.BOOST
            PackOneShotGateControl.BOV -> OneShotGateControl.BOV
            PackOneShotGateControl.BOV_DECAY -> OneShotGateControl.BOV_DECAY
            PackOneShotGateControl.DRIVETRAIN_SPEED -> OneShotGateControl.DRIVETRAIN_SPEED
            PackOneShotGateControl.DECAY -> OneShotGateControl.DECAY
        },
        minimum = minimum,
        maximum = maximum,
        includeMinimum = includeMinimum,
        includeMaximum = includeMaximum,
    )

private fun PackOneShotNodeV2.toRuntimeNode(): OneShotNodeSpec = when (this) {
    is PackOneShotGroupNodeV2 -> OneShotGroupNodeSpec(
        id = id,
        triggerChance = triggerChance,
        playMode = when (playMode) {
            PackOneShotPlayMode.NORMAL -> OneShotPlayMode.NORMAL
            PackOneShotPlayMode.SMART_RANDOM -> OneShotPlayMode.SMART_RANDOM
            PackOneShotPlayMode.SEQUENTIAL -> OneShotPlayMode.SEQUENTIAL
        },
        selectionMode = when (selectionMode) {
            PackOneShotSelectionMode.NORMAL -> OneShotSelectionMode.NORMAL
            PackOneShotSelectionMode.SELECT_ALL -> OneShotSelectionMode.SELECT_ALL
        },
        members = members.map { member ->
            OneShotGroupMemberSpec(member.nodeId, member.weight, member.order)
        },
    )
    is PackOneShotTrackNodeV2 -> OneShotTrackNodeSpec(
        id = id,
        triggerChance = triggerChance,
        effectId = trackId,
        parameterGates = parameterGates.map { gate ->
            gate.toRuntimeGate()
        },
        rpmAmplitudeCurve = rpmCurve.toAutomationCurve(),
        throttleAmplitudeCurve = gainCurve.toAutomationCurve(),
        liveVarispeed = liveVarispeed,
        rootRpm = rootRpm,
        captureControlValues = captureControlValues.map { value ->
            OneShotControlValueSpec(value.control.toRuntimeControl(), value.value)
        },
        controlGainCurves = controlGainCurves.map { controlCurve ->
            OneShotControlCurveSpec(
                controlCurve.control.toRuntimeControl(),
                requireNotNull(controlCurve.curve.toAutomationCurve()),
            )
        },
        pitchAutomations = pitchAutomations.map { automation ->
            OneShotPitchAutomationSpec(
                automation.control.toRuntimeControl(), automation.captureSemitones,
                requireNotNull(automation.playbackRateCurve.toAutomationCurve()),
            )
        },
        sourceVerificationPayloadSha256 = sourceVerificationPayloadSha256,
        zeroGainVirtualization = ZeroGainVirtualizationSpec(
            kind = when (zeroGainVirtualization.kind) {
                PackZeroGainVirtualizationKind.NOT_APPLICABLE ->
                    ZeroGainVirtualizationKind.NOT_APPLICABLE
                PackZeroGainVirtualizationKind.EXACT_ZERO_GATE_THEN_HOLD_DECODE_AND_LOGICAL_PHASE ->
                    ZeroGainVirtualizationKind.EXACT_ZERO_GATE_THEN_HOLD_DECODE_AND_LOGICAL_PHASE
                PackZeroGainVirtualizationKind.ADVANCE_DECODE_AND_LOGICAL_PHASE_WHILE_EXACT_ZERO ->
                    ZeroGainVirtualizationKind.ADVANCE_DECODE_AND_LOGICAL_PHASE_WHILE_EXACT_ZERO
            },
            phaseHoldLatencyWriterFrames = zeroGainVirtualization.phaseHoldLatencyWriterFrames,
            transition = zeroGainVirtualization.transition?.let { transition ->
                ZeroGainTransitionSpec(
                    policy = when (transition.policy) {
                        PackZeroGainTransitionPolicy.IMMEDIATE_EXACT_ZERO ->
                            ZeroGainTransitionPolicy.IMMEDIATE_EXACT_ZERO
                        PackZeroGainTransitionPolicy.RETAIN_PRE_ZERO_GAIN_THEN_LINEAR_FADE_TO_EXACT_ZERO ->
                            ZeroGainTransitionPolicy.RETAIN_PRE_ZERO_GAIN_THEN_LINEAR_FADE_TO_EXACT_ZERO
                    },
                    retainPreZeroGainWriterFrames = transition.retainPreZeroGainWriterFrames,
                    linearFadeWriterFrames = transition.linearFadeWriterFrames,
                    pitchDuringTransition = when (transition.pitchDuringTransition) {
                        PackZeroGainTransitionPitch.LIVE_CURRENT_RPM_PITCH ->
                            ZeroGainTransitionPitch.LIVE_CURRENT_RPM_PITCH
                        PackZeroGainTransitionPitch.AUTHORED_STATIC_BAKED_PITCH ->
                            ZeroGainTransitionPitch.AUTHORED_STATIC_BAKED_PITCH
                    },
                    phaseTreatment = when (transition.phaseTreatment) {
                        PackZeroGainTransitionPhaseTreatment.RETAIN_CURRENT_LOGICAL_PHASE_NO_OFFSET ->
                            ZeroGainTransitionPhaseTreatment.RETAIN_CURRENT_LOGICAL_PHASE_NO_OFFSET
                        PackZeroGainTransitionPhaseTreatment.APPLY_SOURCE_BOUND_CAPTURE_PCM_RESTORE_PHASE_OFFSET ->
                            ZeroGainTransitionPhaseTreatment.APPLY_SOURCE_BOUND_CAPTURE_PCM_RESTORE_PHASE_OFFSET
                    },
                    restoreCapturePcmPhaseOffsetFrames =
                        transition.restoreCapturePcmPhaseOffsetFrames,
                )
            },
        ),
        engineTransientReentryPolicy = when (engineTransientReentryPolicy) {
            PackEngineTransientReentryPolicy.CONTINUE_PRIOR_VOICE_AND_SCHEDULE_NEW_OVERLAPPING_VOICE ->
                EngineTransientReentryPolicy.CONTINUE_PRIOR_VOICE_AND_SCHEDULE_NEW_OVERLAPPING_VOICE
            PackEngineTransientReentryPolicy.NO_NEW_VOICE_ON_PARAMETER_REGION_REENTRY_AFTER_INITIAL_SOURCE_TRIGGER ->
                EngineTransientReentryPolicy.NO_NEW_VOICE_ON_PARAMETER_REGION_REENTRY_AFTER_INITIAL_SOURCE_TRIGGER
        },
    )
    is PackOneShotSilentNodeV2 -> OneShotSilentNodeSpec(
        id = id,
        triggerChance = triggerChance,
        sourceGuid = sourceGuid,
        resolvedRole = resolvedRole.name,
        sourceVerificationPayloadSha256 = sourceVerificationPayloadSha256,
    )
}

private fun PackOneShotGateControl.toRuntimeControl(): OneShotGateControl = when (this) {
    PackOneShotGateControl.ENGINE_RPM -> OneShotGateControl.ENGINE_RPM
    PackOneShotGateControl.ACCELERATOR -> OneShotGateControl.ACCELERATOR
    PackOneShotGateControl.SHIFT_STATE -> OneShotGateControl.SHIFT_STATE
    PackOneShotGateControl.BOOST -> OneShotGateControl.BOOST
    PackOneShotGateControl.BOV -> OneShotGateControl.BOV
    PackOneShotGateControl.BOV_DECAY -> OneShotGateControl.BOV_DECAY
    PackOneShotGateControl.DRIVETRAIN_SPEED -> OneShotGateControl.DRIVETRAIN_SPEED
    PackOneShotGateControl.DECAY -> OneShotGateControl.DECAY
}

private fun PackOneShotTrigger.toRuntimeTrigger(): SampleEffectTrigger = when (this) {
    PackOneShotTrigger.LIMITER -> SampleEffectTrigger.LIMITER
    PackOneShotTrigger.LIMITER_EVENT -> SampleEffectTrigger.LIMITER_EVENT
    PackOneShotTrigger.SHIFT_UP -> SampleEffectTrigger.SHIFT_UP
    PackOneShotTrigger.SHIFT_DOWN -> SampleEffectTrigger.SHIFT_DOWN
    PackOneShotTrigger.THROTTLE_LIFT -> SampleEffectTrigger.THROTTLE_LIFT
    PackOneShotTrigger.BOV_LIFT -> SampleEffectTrigger.BOV_LIFT
    PackOneShotTrigger.ENGINE_EVENT -> SampleEffectTrigger.ENGINE_EVENT
    PackOneShotTrigger.TURBO_EVENT -> SampleEffectTrigger.TURBO_EVENT
}

private fun SoundTrackManifestV1.toEffect(
    limiterPolicy: PackLimiterEventPolicyV2? = null,
): SampleEffectSpec {
    val authoredTrigger = triggers.singleOrNull()
    val runtimeTrigger = when (authoredTrigger) {
        null -> when (role) {
            PackTrackRole.TURBO, PackTrackRole.SPOOL -> SampleEffectTrigger.CONTINUOUS_LOOP
            PackTrackRole.TRANSMISSION -> SampleEffectTrigger.TRANSMISSION_LOOP
            else -> error("${role.name} requires an authored trigger")
        }
        "limiterPulse" -> SampleEffectTrigger.LIMITER
        "limiterEvent" -> SampleEffectTrigger.LIMITER_EVENT
        "shiftUp" -> SampleEffectTrigger.SHIFT_UP
        "shiftDown" -> SampleEffectTrigger.SHIFT_DOWN
        "bov" -> SampleEffectTrigger.BOV_LIFT
        "overrunRelease", "pop", "bang", "crack" -> SampleEffectTrigger.THROTTLE_LIFT
        "engineEvent" -> SampleEffectTrigger.ENGINE_EVENT
        "turboEvent" -> SampleEffectTrigger.TURBO_EVENT
        else -> error("Unsupported authored trigger $authoredTrigger")
    }
    val mapping = when (role) {
        PackTrackRole.TURBO -> EffectMapping(
            SampleEffectControls.turbo, runtimeTrigger, "Turbo",
            turboAudioResponse = TurboAudioResponse.BOOST,
        )
        PackTrackRole.SPOOL -> EffectMapping(
            SampleEffectControls.turbo, runtimeTrigger, "Spool",
            turboAudioResponse = TurboAudioResponse.BOOST,
        )
        PackTrackRole.BOV -> EffectMapping(
            SampleEffectControls.turbo, runtimeTrigger, "Blow-off valve",
            turboAudioResponse = TurboAudioResponse.BOOST,
        )
        PackTrackRole.TURBO_TRANSIENT -> EffectMapping(
            SampleEffectControls.turbo, runtimeTrigger, "Turbo transient",
        )
        PackTrackRole.TRANSMISSION -> EffectMapping(
            SampleEffectControls.transmission, runtimeTrigger, "Transmission whine",
        )
        PackTrackRole.LIMITER -> EffectMapping(SampleEffectControls.limiter, runtimeTrigger, "Limiter")
        PackTrackRole.SHIFT_UP -> EffectMapping(SampleEffectControls.gearChanges, runtimeTrigger, "Shift up")
        PackTrackRole.SHIFT_DOWN -> EffectMapping(SampleEffectControls.gearChanges, runtimeTrigger, "Shift down")
        PackTrackRole.OVERRUN -> EffectMapping(
            SampleEffectControls.exhaustOverrun, runtimeTrigger, "Exhaust overrun", true,
        )
        PackTrackRole.POP -> EffectMapping(
            SampleEffectControls.popsBangsCracks, runtimeTrigger, "Pop", true,
        )
        PackTrackRole.BANG -> EffectMapping(
            SampleEffectControls.popsBangsCracks, runtimeTrigger, "Bang", true,
        )
        PackTrackRole.CRACK -> EffectMapping(
            SampleEffectControls.popsBangsCracks, runtimeTrigger, "Crack", true,
        )
        PackTrackRole.ENGINE_TRANSIENT -> EffectMapping(
            SampleEffectControls.coreEngine, runtimeTrigger, "Engine transient",
            coreEngineTransient = true,
        )
        else -> error("${role.name} is not an effect")
    }
    return SampleEffectSpec(
        id = id,
        control = mapping.control,
        assetName = id,
        trigger = mapping.trigger,
        baseGainDb = gainDb,
        displayName = mapping.displayName,
        auditionable = mapping.auditionable,
        rpmAmplitudeCurve = rpmCurve.toAutomationCurve(),
        throttleAmplitudeCurve = gainCurve.toAutomationCurve(),
        autopitchRootRpm = rootRpm,
        authoredRelativeRateCurve = if (
            pitchMode == PackTrackPitchMode.AUTHORED_PROPERTY_ONE_RELATIVE_RATE
        ) {
            requireNotNull(pitchCurve.toAutomationCurve())
        } else {
            null
        },
        turboAudioResponse = if (runtimeTrigger == SampleEffectTrigger.TURBO_EVENT) {
            TurboAudioResponse.NONE
        } else {
            mapping.turboAudioResponse
        },
        coreEngineTransient = mapping.coreEngineTransient,
        polyphonicTemplate = mapping.coreEngineTransient ||
            runtimeTrigger == SampleEffectTrigger.TURBO_EVENT ||
            (role == PackTrackRole.LIMITER && limiterPolicy?.programMode ==
                PackLimiterProgramMode.PERSISTENT_DECAY_REGION_ONE_SHOT),
        looping = if (role == PackTrackRole.LIMITER && limiterPolicy != null) {
            limiterPolicy.programMode != PackLimiterProgramMode.PERSISTENT_DECAY_REGION_ONE_SHOT
        } else {
            runtimeTrigger == SampleEffectTrigger.CONTINUOUS_LOOP ||
                runtimeTrigger == SampleEffectTrigger.TRANSMISSION_LOOP
        },
        startsActive = role != PackTrackRole.LIMITER &&
            (runtimeTrigger == SampleEffectTrigger.CONTINUOUS_LOOP ||
                runtimeTrigger == SampleEffectTrigger.TRANSMISSION_LOOP),
        softwareVoicePriority = softwareChannelPriority,
    )
}

private data class EffectMapping(
    val control: SampleEffectControlSpec,
    val trigger: SampleEffectTrigger,
    val displayName: String,
    val auditionable: Boolean = false,
    val turboAudioResponse: TurboAudioResponse = TurboAudioResponse.NONE,
    val coreEngineTransient: Boolean = false,
)

internal fun CarEngineMetadata.toTurboControllerBank(): TurboControllerBankSpec? {
    if (turboControllers.isEmpty()) return null
    val programs = Array(turboControllers.size) { fileIndex ->
        val file = turboControllers[fileIndex]
        TurboControllerProgramSpec(
            sourceFile = file.file,
            stages = Array(file.controllers.size) { controllerIndex ->
                file.controllers[controllerIndex].toRuntimeStage()
            },
        )
    }
    return TurboControllerBankSpec(turboCount = turboCount, programs = programs)
}

internal fun CarEngineMetadata.toTurboPhysicsSpec(
    controllerBank: TurboControllerBankSpec? = toTurboControllerBank(),
): TurboPhysicsSpec? {
    if (turboPhysics.turbos.isEmpty()) return null
    val units = Array(turboPhysics.turbos.size) { turboIndex ->
        val metadata = turboPhysics.turbos[turboIndex]
        val controllerIndex = metadata.controllerFile?.let { sourceFile ->
            requireNotNull(controllerBank) { "Missing controller bank for $sourceFile" }
                .programIndex(sourceFile)
                .also { require(it >= 0) { "Missing turbo controller $sourceFile" } }
        } ?: -1
        TurboPhysicsUnitSpec(
            maximumBoost = metadata.maximumBoost,
            wastegate = metadata.wastegate,
            referenceRpm = metadata.referenceRpm,
            gamma = metadata.gamma,
            lagUp = metadata.lagUp,
            lagDown = metadata.lagDown,
            controllerProgramIndex = controllerIndex,
        )
    }
    return TurboPhysicsSpec(
        bovPressureThreshold = turboPhysics.bovPressureThreshold,
        units = units,
        controllerBank = controllerBank,
    )
}

private fun TurboControllerMetadata.toRuntimeStage(): TurboControllerStageSpec {
    val runtimeInput = when (input) {
        "RPMS" -> TurboControllerInput.RPM
        "GAS" -> TurboControllerInput.THROTTLE
        "GEAR" -> TurboControllerInput.GEAR
        else -> error("Unsupported turbo controller input $input")
    }
    val runtimeCombinator = when (combinator) {
        "ADD" -> TurboControllerCombinator.ADD
        "MULT" -> TurboControllerCombinator.MULTIPLY
        else -> error("Unsupported turbo controller combinator $combinator")
    }
    val inputs = DoubleArray(lut.size)
    val outputs = DoubleArray(lut.size)
    var index = 0
    while (index < lut.size) {
        inputs[index] = lut[index].input
        outputs[index] = lut[index].output
        index += 1
    }
    return TurboControllerStageSpec(
        input = runtimeInput,
        combinator = runtimeCombinator,
        inputPoints = inputs,
        outputPoints = outputs,
        filter = filter,
        downLimit = downLimit,
        upLimit = upLimit,
    )
}

private fun EngineGasAssistMetadata.toRuntimeSpec(): EngineGasAssistSpec {
    val times = DoubleArray(autoBlipProfile.size)
    val pedals = DoubleArray(autoBlipProfile.size)
    var index = 0
    while (index < autoBlipProfile.size) {
        times[index] = autoBlipProfile[index].input
        pedals[index] = autoBlipProfile[index].output
        index += 1
    }
    return EngineGasAssistSpec(
        autoShifterGasCutoffMs = autoShifterGasCutoffMs,
        engineCutoffMs = engineCutoffMs,
        autoBlipElectronic = autoBlipElectronic,
        autoBlipTimesMs = times,
        autoBlipPedals = pedals,
        autoBlipEndTimeMs = autoBlipEndTimeMs,
    )
}

private fun List<CurvePointV1>.toAutomationCurve(): AutomationCurve? =
    takeIf(List<*>::isNotEmpty)?.let { curve ->
        AutomationCurve(curve.map { point -> CurvePoint(point.input, point.output) })
    }

private object SoundFamilyManifestV1Audio {
    const val SAMPLE_RATE = 48_000
}
