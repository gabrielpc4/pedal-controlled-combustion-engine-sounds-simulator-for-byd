#include <FLAC/stream_decoder.h>
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cmath>
#include <limits>
#include <memory>
#include <string>
#include <vector>

namespace {

struct DecodeCancellation {
    std::atomic_bool cancelled{false};
};

struct DecodedClip {
    uint32_t sample_rate = 0;
    uint32_t channels = 0;
    uint32_t bits_per_sample = 0;
    uint64_t expected_frames = 0;
    uint64_t max_decoded_bytes = 0;
    std::vector<std::vector<int16_t>> planar;
    bool metadata_seen = false;
    bool failed = false;
    std::string error;
    DecodeCancellation* cancellation = nullptr;
};

struct MixVoice {
    DecodedClip* clip = nullptr;
    int start = 0;
    int end = 0;
    int crossfade = 0;
    bool looping = true;
    bool active = true;
    bool has_looped = false;
    double phase = 0.0;
    double gain = 0.0;
    // A positive start command may target an exact frame inside this render burst.
    int start_delay_frames = 0;
    // Immutable PCM/timeline values are cached when the mixer is built. The
    // render thread must not repeatedly traverse std::vector or recompute loop
    // geometry for every channel of every sample.
    const int16_t* left = nullptr;
    const int16_t* right = nullptr;
    int frame_count = 0;
    int loop_length = 0;
    int crossfade_start = 0;
    int resume = 0;
    int effective_loop_length = 0;
    // Java control arrays are constant for one render burst. Snapshot them into
    // the voice once so the inner loop only touches its compact native state.
    double target_gain = 0.0;
    double increment = 1.0;
    bool zero_transition_active = false;
    int zero_transition_elapsed_frames = 0;
    int zero_transition_retain_frames = 0;
    int zero_transition_fade_frames = 0;
    double zero_transition_start_gain = 0.0;
    int phase_advance_frames = 0;
};

struct NativeMixer {
    std::vector<MixVoice> loops;
    std::vector<MixVoice> effects;
    // Preallocated stock-FMOD software voices. They borrow immutable PCM from
    // `effects` and are rebound only by primitive commands at render-block boundaries.
    std::vector<MixVoice> dynamic_effects;
    double master = 0.0;
    double profile = 1.0;
    double enabled = 0.0;
    double continuous = 1.0;
    int64_t frames = 0;
    int64_t wraps = 0;
    int64_t over_range = 0;
};

inline double zero_transition_gain(const MixVoice& voice) {
    if (voice.zero_transition_elapsed_frames < voice.zero_transition_retain_frames) {
        return voice.zero_transition_start_gain;
    }
    if (voice.zero_transition_fade_frames <= 0) return 0.0;
    const int fade_elapsed =
        voice.zero_transition_elapsed_frames - voice.zero_transition_retain_frames;
    if (fade_elapsed >= voice.zero_transition_fade_frames) return 0.0;
    return voice.zero_transition_start_gain *
        static_cast<double>(voice.zero_transition_fade_frames - fade_elapsed) /
        static_cast<double>(voice.zero_transition_fade_frames);
}

void fail(DecodedClip* clip, const char* message) {
    clip->failed = true;
    if (clip->error.empty()) clip->error = message;
}

void metadata_callback(
    const FLAC__StreamDecoder*,
    const FLAC__StreamMetadata* metadata,
    void* client_data
) {
    auto* clip = static_cast<DecodedClip*>(client_data);
    if (metadata->type != FLAC__METADATA_TYPE_STREAMINFO) return;
    const auto& info = metadata->data.stream_info;
    if (info.channels == 0 || info.channels > 2) {
        fail(clip, "Only mono and stereo FLAC streams are supported");
        return;
    }
    if (info.bits_per_sample != 16) {
        fail(clip, "Sound packs must contain 16-bit FLAC");
        return;
    }
    if (info.sample_rate != 48000) {
        fail(clip, "Sound packs must contain 48 kHz FLAC");
        return;
    }
    // Imported packs are compiler-produced and must declare an exact frame
    // count. Without it std::vector growth could temporarily exceed the Java
    // reservation even though the final decoded size fits the limit.
    if (info.total_samples == 0) {
        fail(clip, "Sound-pack FLAC must declare total samples in STREAMINFO");
        return;
    }
    clip->sample_rate = info.sample_rate;
    clip->channels = info.channels;
    clip->bits_per_sample = info.bits_per_sample;
    clip->expected_frames = info.total_samples;
    const uint64_t decoded_bytes = info.total_samples * info.channels * sizeof(int16_t);
    if (decoded_bytes / info.channels / sizeof(int16_t) != info.total_samples ||
        (clip->max_decoded_bytes > 0 && decoded_bytes > clip->max_decoded_bytes)) {
        fail(clip, "FLAC exceeds the decoded-audio memory budget");
        return;
    }
    clip->planar.assign(info.channels, {});
    if (info.total_samples <= static_cast<uint64_t>(std::numeric_limits<size_t>::max())) {
        for (auto& channel : clip->planar) {
            channel.reserve(static_cast<size_t>(info.total_samples));
        }
    }
    clip->metadata_seen = true;
}

FLAC__StreamDecoderWriteStatus write_callback(
    const FLAC__StreamDecoder*,
    const FLAC__Frame* frame,
    const FLAC__int32* const buffer[],
    void* client_data
) {
    auto* clip = static_cast<DecodedClip*>(client_data);
    if (clip->cancellation != nullptr && clip->cancellation->cancelled.load(std::memory_order_relaxed)) {
        fail(clip, "FLAC decode cancelled");
        return FLAC__STREAM_DECODER_WRITE_STATUS_ABORT;
    }
    if (clip->failed || !clip->metadata_seen) return FLAC__STREAM_DECODER_WRITE_STATUS_ABORT;
    const size_t block_size = frame->header.blocksize;
    const uint64_t previous_frames = clip->planar.empty() ? 0 : clip->planar.front().size();
    const uint64_t next_frames = previous_frames + block_size;
    const uint64_t next_bytes = next_frames * clip->channels * sizeof(int16_t);
    if (next_frames < previous_frames ||
        (clip->max_decoded_bytes > 0 && next_bytes > clip->max_decoded_bytes)) {
        fail(clip, "FLAC exceeds the decoded-audio memory budget");
        return FLAC__STREAM_DECODER_WRITE_STATUS_ABORT;
    }
    for (uint32_t channel = 0; channel < clip->channels; ++channel) {
        auto& destination = clip->planar[channel];
        const auto* source = buffer[channel];
        const size_t previous_size = destination.size();
        destination.resize(previous_size + block_size);
        for (size_t frame_index = 0; frame_index < block_size; ++frame_index) {
            destination[previous_size + frame_index] = static_cast<int16_t>(
                std::clamp<FLAC__int32>(source[frame_index], -32768, 32767)
            );
        }
    }
    return FLAC__STREAM_DECODER_WRITE_STATUS_CONTINUE;
}

void error_callback(
    const FLAC__StreamDecoder*,
    FLAC__StreamDecoderErrorStatus status,
    void* client_data
) {
    auto* clip = static_cast<DecodedClip*>(client_data);
    clip->failed = true;
    clip->error = FLAC__StreamDecoderErrorStatusString[status];
}

void throw_illegal_state(JNIEnv* env, const std::string& message) {
    jclass type = env->FindClass("java/lang/IllegalStateException");
    if (type != nullptr) env->ThrowNew(type, message.c_str());
}

DecodedClip* from_handle(jlong handle) {
    return reinterpret_cast<DecodedClip*>(static_cast<intptr_t>(handle));
}

struct StereoSample {
    double left;
    double right;
};

struct CubicWeights {
    double y0;
    double y1;
    double y2;
    double y3;
};

inline CubicWeights cubic_weights(double fraction) noexcept {
    // Algebraically identical to the reference renderer's cubic polynomial,
    // with weights shared by the left and right channels.
    const double squared = fraction * fraction;
    const double cubed = squared * fraction;
    return {
        -cubed + 2.0 * squared - fraction,
        cubed - 2.0 * squared + 1.0,
        -cubed + squared + fraction,
        cubed - squared,
    };
}

inline int clamp_frame(int frame, int frame_count) noexcept {
    if (frame < 0) return 0;
    if (frame >= frame_count) return frame_count - 1;
    return frame;
}

inline int resolve_loop_frame(const MixVoice& voice, int frame) noexcept {
    if (frame >= voice.end) {
        frame = voice.start + (frame - voice.end) % voice.loop_length;
    } else if (voice.has_looped && frame < voice.start) {
        frame = voice.end - 1 - ((voice.start - 1 - frame) % voice.loop_length);
    }
    return clamp_frame(frame, voice.frame_count);
}

inline StereoSample interpolate_stereo(
    const MixVoice& voice,
    int i0,
    int i1,
    int i2,
    int i3,
    const CubicWeights& weights
) noexcept {
    constexpr double pcm_scale = 1.0 / 32768.0;
    const auto* left = voice.left;
    const auto* right = voice.right;
    return {
        (static_cast<double>(left[i0]) * weights.y0 +
         static_cast<double>(left[i1]) * weights.y1 +
         static_cast<double>(left[i2]) * weights.y2 +
         static_cast<double>(left[i3]) * weights.y3) * pcm_scale,
        (static_cast<double>(right[i0]) * weights.y0 +
         static_cast<double>(right[i1]) * weights.y1 +
         static_cast<double>(right[i2]) * weights.y2 +
         static_cast<double>(right[i3]) * weights.y3) * pcm_scale,
    };
}

inline StereoSample clamped_cubic_stereo(
    const MixVoice& voice,
    int frame,
    const CubicWeights& weights
) noexcept {
    return interpolate_stereo(
        voice,
        clamp_frame(frame - 1, voice.frame_count),
        clamp_frame(frame, voice.frame_count),
        clamp_frame(frame + 1, voice.frame_count),
        clamp_frame(frame + 2, voice.frame_count),
        weights
    );
}

inline StereoSample cubic_voice_stereo(const MixVoice& voice) noexcept {
    const int frame = static_cast<int>(voice.phase);
    const double fraction = voice.phase - frame;
    const CubicWeights weights = cubic_weights(fraction);

    if (voice.looping && voice.crossfade > 0 && voice.phase >= voice.crossfade_start) {
        const double offset = voice.phase - voice.crossfade_start;
        const double blend_x = std::clamp(offset / voice.crossfade, 0.0, 1.0);
        const double blend = blend_x * blend_x * (3.0 - 2.0 * blend_x);
        const StereoSample tail = clamped_cubic_stereo(voice, frame, weights);
        // start and crossfade_start are integers, so the head has exactly the
        // same fractional phase and therefore the same cubic weights.
        const int head_frame = voice.start + static_cast<int>(offset);
        const StereoSample head = clamped_cubic_stereo(voice, head_frame, weights);
        return {
            tail.left + (head.left - tail.left) * blend,
            tail.right + (head.right - tail.right) * blend,
        };
    }

    int i0 = frame - 1;
    int i1 = frame;
    int i2 = frame + 1;
    int i3 = frame + 2;
    if (voice.looping &&
        (i3 >= voice.end || (voice.has_looped && i0 < voice.start))) {
        i0 = resolve_loop_frame(voice, i0);
        i1 = resolve_loop_frame(voice, i1);
        i2 = resolve_loop_frame(voice, i2);
        i3 = resolve_loop_frame(voice, i3);
    } else if (i0 < 0 || i3 >= voice.frame_count) {
        i0 = clamp_frame(i0, voice.frame_count);
        i1 = clamp_frame(i1, voice.frame_count);
        i2 = clamp_frame(i2, voice.frame_count);
        i3 = clamp_frame(i3, voice.frame_count);
    }
    return interpolate_stereo(voice, i0, i1, i2, i3, weights);
}

void bind_voice_pcm(MixVoice& voice) {
    voice.left = voice.clip->planar[0].data();
    voice.right = voice.clip->planar[std::min<uint32_t>(1, voice.clip->channels - 1)].data();
    voice.frame_count = static_cast<int>(voice.clip->planar[0].size());
    voice.loop_length = voice.end - voice.start;
    voice.crossfade_start = voice.end - voice.crossfade;
    voice.resume = voice.start + voice.crossfade;
    voice.effective_loop_length = voice.end - voice.resume;
}

inline bool advance_voice(MixVoice& voice) noexcept {
    if (!voice.active) return false;
    voice.phase += voice.increment;
    if (!voice.looping) {
        if (voice.phase >= static_cast<double>(voice.frame_count - 1)) {
            voice.active = false;
            voice.gain = 0.0;
        }
        return false;
    }
    if (voice.phase < voice.end) return false;
    const double overshoot = voice.phase - voice.end;
    voice.phase = voice.resume + (
        overshoot < voice.effective_loop_length
            ? overshoot
            : std::fmod(overshoot, static_cast<double>(voice.effective_loop_length))
    );
    voice.has_looped = true;
    return true;
}

double limit_sample(double value) {
    constexpr double ceiling = 0.8912509381337456; // -1 dBFS
    constexpr double knee = 0.7079457843841379;    // -3 dBFS
    const double magnitude = std::abs(value);
    if (magnitude <= knee) return value;
    const double width = ceiling - knee;
    const double limited = knee + width * (1.0 - std::exp(-(magnitude - knee) / width));
    return std::copysign(limited, value);
}

DecodeCancellation* cancellation_from_handle(jlong handle) {
    return reinterpret_cast<DecodeCancellation*>(static_cast<intptr_t>(handle));
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_gabrielpc_enginesoundsimulator_audio_NativeFlacDecoder_nativeDecode(
    JNIEnv* env,
    jobject,
    jstring path_value,
    jlong max_decoded_bytes,
    jlong cancellation_handle
) {
    if (path_value == nullptr) {
        throw_illegal_state(env, "FLAC path is null");
        return 0;
    }
    const char* path_chars = env->GetStringUTFChars(path_value, nullptr);
    if (path_chars == nullptr) return 0;
    const std::string path(path_chars);
    env->ReleaseStringUTFChars(path_value, path_chars);

    auto clip = std::make_unique<DecodedClip>();
    clip->cancellation = cancellation_from_handle(cancellation_handle);
    clip->max_decoded_bytes = max_decoded_bytes <= 0
        ? 0
        : static_cast<uint64_t>(max_decoded_bytes);
    FLAC__StreamDecoder* decoder = FLAC__stream_decoder_new();
    if (decoder == nullptr) {
        throw_illegal_state(env, "Unable to allocate FLAC decoder");
        return 0;
    }
    FLAC__stream_decoder_set_md5_checking(decoder, true);
    const auto init_status = FLAC__stream_decoder_init_file(
        decoder,
        path.c_str(),
        write_callback,
        metadata_callback,
        error_callback,
        clip.get()
    );
    if (init_status != FLAC__STREAM_DECODER_INIT_STATUS_OK) {
        const std::string message = FLAC__StreamDecoderInitStatusString[init_status];
        FLAC__stream_decoder_delete(decoder);
        throw_illegal_state(env, "FLAC initialization failed: " + message);
        return 0;
    }
    const bool processed = FLAC__stream_decoder_process_until_end_of_stream(decoder);
    // finish() performs libFLAC's STREAMINFO MD5 verification. Treat a mismatch as
    // a corrupt decode even if every audio frame was delivered successfully.
    const bool verified = FLAC__stream_decoder_finish(decoder);
    FLAC__stream_decoder_delete(decoder);

    if (!processed || !verified || clip->failed || !clip->metadata_seen) {
        const std::string detail = clip->error.empty() ? "invalid or incomplete stream" : clip->error;
        throw_illegal_state(env, "FLAC decode failed: " + detail);
        return 0;
    }
    const uint64_t frames = clip->planar.empty() ? 0 : clip->planar.front().size();
    if (frames == 0 || (clip->expected_frames != 0 && frames != clip->expected_frames)) {
        throw_illegal_state(env, "FLAC decoded frame count does not match STREAMINFO");
        return 0;
    }
    for (const auto& channel : clip->planar) {
        if (channel.size() != frames) {
            throw_illegal_state(env, "FLAC channels have mismatched frame counts");
            return 0;
        }
    }
    return static_cast<jlong>(reinterpret_cast<intptr_t>(clip.release()));
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_gabrielpc_enginesoundsimulator_audio_NativeFlacDecoder_nativeCreateCancellation(
    JNIEnv*, jobject
) {
    auto cancellation = std::make_unique<DecodeCancellation>();
    return static_cast<jlong>(reinterpret_cast<intptr_t>(cancellation.release()));
}

extern "C" JNIEXPORT void JNICALL
Java_com_gabrielpc_enginesoundsimulator_audio_NativeFlacDecoder_nativeCancel(
    JNIEnv*, jobject, jlong handle
) {
    auto* cancellation = cancellation_from_handle(handle);
    if (cancellation != nullptr) {
        cancellation->cancelled.store(true, std::memory_order_relaxed);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_gabrielpc_enginesoundsimulator_audio_NativeFlacDecoder_nativeReleaseCancellation(
    JNIEnv*, jobject, jlong handle
) {
    delete cancellation_from_handle(handle);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_gabrielpc_enginesoundsimulator_audio_NativeFlacDecoder_nativeCreateTestClip(
    JNIEnv* env, jobject, jshortArray interleaved
) {
    const jsize samples = env->GetArrayLength(interleaved);
    if (samples < 8 || samples % 2 != 0) return 0;
    auto clip = std::make_unique<DecodedClip>();
    clip->sample_rate = 48000; clip->channels = 2; clip->bits_per_sample = 16;
    clip->metadata_seen = true; clip->expected_frames = samples / 2;
    clip->planar.assign(2, std::vector<int16_t>(samples / 2));
    jshort* source = env->GetShortArrayElements(interleaved, nullptr);
    for (jsize i = 0; i < samples / 2; ++i) {
        clip->planar[0][i] = source[i * 2]; clip->planar[1][i] = source[i * 2 + 1];
    }
    env->ReleaseShortArrayElements(interleaved, source, JNI_ABORT);
    return reinterpret_cast<jlong>(clip.release());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_gabrielpc_enginesoundsimulator_audio_NativeFlacDecoder_nativeSampleRate(
    JNIEnv*, jobject, jlong handle
) {
    const auto* clip = from_handle(handle);
    return clip == nullptr ? 0 : static_cast<jint>(clip->sample_rate);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_gabrielpc_enginesoundsimulator_audio_NativeFlacDecoder_nativeChannels(
    JNIEnv*, jobject, jlong handle
) {
    const auto* clip = from_handle(handle);
    return clip == nullptr ? 0 : static_cast<jint>(clip->channels);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_gabrielpc_enginesoundsimulator_audio_NativeFlacDecoder_nativeFrames(
    JNIEnv*, jobject, jlong handle
) {
    const auto* clip = from_handle(handle);
    return clip == nullptr || clip->planar.empty() ? 0 : static_cast<jlong>(clip->planar.front().size());
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_gabrielpc_enginesoundsimulator_audio_NativeFlacDecoder_nativeChannelBuffer(
    JNIEnv* env, jobject, jlong handle, jint channel
) {
    auto* clip = from_handle(handle);
    if (clip == nullptr || channel < 0 || static_cast<uint32_t>(channel) >= clip->channels) {
        throw_illegal_state(env, "Invalid native FLAC channel");
        return nullptr;
    }
    auto& samples = clip->planar[static_cast<size_t>(channel)];
    return env->NewDirectByteBuffer(samples.data(), static_cast<jlong>(samples.size() * sizeof(int16_t)));
}

extern "C" JNIEXPORT void JNICALL
Java_com_gabrielpc_enginesoundsimulator_audio_NativeFlacDecoder_nativeRelease(
    JNIEnv*, jobject, jlong handle
) {
    delete from_handle(handle);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_gabrielpc_enginesoundsimulator_audio_NativePcmMixer_00024Companion_nativeCreate(
    JNIEnv* env, jobject, jlongArray loop_handles, jintArray loop_starts, jintArray loop_ends,
    jintArray loop_crossfades, jlongArray effect_handles, jintArray effect_starts,
    jintArray effect_ends, jbooleanArray effect_loops, jint dynamic_effect_count
) {
    if (dynamic_effect_count < 0 || dynamic_effect_count > 256) {
        throw_illegal_state(env, "Invalid dynamic effect voice count");
        return 0;
    }
    auto mixer = std::make_unique<NativeMixer>();
    const jsize loop_count = env->GetArrayLength(loop_handles);
    const jsize effect_count = env->GetArrayLength(effect_handles);
    jlong* lh = env->GetLongArrayElements(loop_handles, nullptr);
    jint* ls = env->GetIntArrayElements(loop_starts, nullptr);
    jint* le = env->GetIntArrayElements(loop_ends, nullptr);
    jint* lc = env->GetIntArrayElements(loop_crossfades, nullptr);
    for (jsize i = 0; i < loop_count; ++i) {
        auto* clip = from_handle(lh[i]);
        if (clip == nullptr || ls[i] < 0 || le[i] <= ls[i] || le[i] > static_cast<jint>(clip->planar[0].size())) {
            throw_illegal_state(env, "Invalid native loop mixer configuration");
            mixer.reset();
            break;
        }
        mixer->loops.push_back({clip, ls[i], le[i], std::clamp(lc[i], 0, (le[i] - ls[i]) / 4)});
        bind_voice_pcm(mixer->loops.back());
    }
    env->ReleaseLongArrayElements(loop_handles, lh, JNI_ABORT);
    env->ReleaseIntArrayElements(loop_starts, ls, JNI_ABORT);
    env->ReleaseIntArrayElements(loop_ends, le, JNI_ABORT);
    env->ReleaseIntArrayElements(loop_crossfades, lc, JNI_ABORT);
    if (mixer == nullptr) return 0;
    jlong* eh = env->GetLongArrayElements(effect_handles, nullptr);
    jint* es = env->GetIntArrayElements(effect_starts, nullptr);
    jint* ee = env->GetIntArrayElements(effect_ends, nullptr);
    jboolean* el = env->GetBooleanArrayElements(effect_loops, nullptr);
    for (jsize i = 0; i < effect_count; ++i) {
        auto* clip = from_handle(eh[i]);
        if (clip == nullptr || es[i] < 0 || ee[i] <= es[i] || ee[i] > static_cast<jint>(clip->planar[0].size())) {
            throw_illegal_state(env, "Invalid native effect mixer configuration");
            mixer.reset();
            break;
        }
        MixVoice voice{clip, es[i], ee[i], 0, el[i] == JNI_TRUE};
        voice.active = voice.looping;
        bind_voice_pcm(voice);
        mixer->effects.push_back(voice);
    }
    env->ReleaseLongArrayElements(effect_handles, eh, JNI_ABORT);
    env->ReleaseIntArrayElements(effect_starts, es, JNI_ABORT);
    env->ReleaseIntArrayElements(effect_ends, ee, JNI_ABORT);
    env->ReleaseBooleanArrayElements(effect_loops, el, JNI_ABORT);
    if (mixer != nullptr) {
        mixer->dynamic_effects.resize(static_cast<size_t>(dynamic_effect_count));
        for (auto& voice : mixer->dynamic_effects) {
            voice.looping = false;
            voice.active = false;
        }
    }
    return mixer == nullptr ? 0 : reinterpret_cast<jlong>(mixer.release());
}

extern "C" JNIEXPORT void JNICALL
Java_com_gabrielpc_enginesoundsimulator_audio_NativePcmMixer_00024Companion_nativeRender(
    JNIEnv* env, jobject, jlong handle, jshortArray output, jint frames,
    jdoubleArray loop_targets, jdoubleArray loop_increments, jintArray loop_real,
    jdoubleArray loop_gains,
    jdoubleArray effect_targets, jdoubleArray effect_increments, jintArray effect_triggers,
    jintArray effect_start_offsets, jintArray effect_real, jdoubleArray effect_gains,
    jintArray effect_active,
    jdoubleArray dynamic_effect_targets, jdoubleArray dynamic_effect_increments,
    jintArray dynamic_effect_commands, jintArray dynamic_effect_start_offsets,
    jdoubleArray dynamic_effect_start_phases, jdoubleArray dynamic_effect_start_gains,
    jintArray dynamic_effect_zero_transition_active,
    jintArray dynamic_effect_zero_transition_elapsed_frames,
    jintArray dynamic_effect_zero_transition_retain_frames,
    jintArray dynamic_effect_zero_transition_fade_frames,
    jdoubleArray dynamic_effect_zero_transition_start_gains,
    jintArray dynamic_effect_phase_advance_frames,
    jdoubleArray dynamic_effect_restore_phase_offsets,
    jdoubleArray dynamic_effect_gains,
    jintArray dynamic_effect_active,
    jdouble target_master, jdouble target_profile, jdouble target_enabled, jdouble target_continuous,
    jdouble master_alpha, jdouble profile_alpha, jdouble enabled_alpha, jdouble layer_alpha,
    jlongArray status_longs, jdoubleArray status_doubles
) {
    auto* mixer = reinterpret_cast<NativeMixer*>(handle);
    if (mixer == nullptr) return;
    auto* out = static_cast<jshort*>(env->GetPrimitiveArrayCritical(output, nullptr));
    auto* lt = static_cast<jdouble*>(env->GetPrimitiveArrayCritical(loop_targets, nullptr));
    auto* li = static_cast<jdouble*>(env->GetPrimitiveArrayCritical(loop_increments, nullptr));
    auto* lr = static_cast<jint*>(env->GetPrimitiveArrayCritical(loop_real, nullptr));
    auto* lg = static_cast<jdouble*>(env->GetPrimitiveArrayCritical(loop_gains, nullptr));
    auto* et = static_cast<jdouble*>(env->GetPrimitiveArrayCritical(effect_targets, nullptr));
    auto* ei = static_cast<jdouble*>(env->GetPrimitiveArrayCritical(effect_increments, nullptr));
    auto* trigger = static_cast<jint*>(env->GetPrimitiveArrayCritical(effect_triggers, nullptr));
    auto* effect_start_offset = static_cast<jint*>(
        env->GetPrimitiveArrayCritical(effect_start_offsets, nullptr)
    );
    auto* er = static_cast<jint*>(env->GetPrimitiveArrayCritical(effect_real, nullptr));
    auto* eg = static_cast<jdouble*>(env->GetPrimitiveArrayCritical(effect_gains, nullptr));
    auto* ea = static_cast<jint*>(env->GetPrimitiveArrayCritical(effect_active, nullptr));
    auto* dt = static_cast<jdouble*>(env->GetPrimitiveArrayCritical(dynamic_effect_targets, nullptr));
    auto* di = static_cast<jdouble*>(env->GetPrimitiveArrayCritical(dynamic_effect_increments, nullptr));
    auto* command = static_cast<jint*>(env->GetPrimitiveArrayCritical(dynamic_effect_commands, nullptr));
    auto* start_offset = static_cast<jint*>(
        env->GetPrimitiveArrayCritical(dynamic_effect_start_offsets, nullptr)
    );
    auto* start_phase = static_cast<jdouble*>(
        env->GetPrimitiveArrayCritical(dynamic_effect_start_phases, nullptr)
    );
    auto* start_gain = static_cast<jdouble*>(
        env->GetPrimitiveArrayCritical(dynamic_effect_start_gains, nullptr)
    );
    auto* zero_transition_active = static_cast<jint*>(
        env->GetPrimitiveArrayCritical(dynamic_effect_zero_transition_active, nullptr)
    );
    auto* zero_transition_elapsed_frames = static_cast<jint*>(
        env->GetPrimitiveArrayCritical(dynamic_effect_zero_transition_elapsed_frames, nullptr)
    );
    auto* zero_transition_retain_frames = static_cast<jint*>(
        env->GetPrimitiveArrayCritical(dynamic_effect_zero_transition_retain_frames, nullptr)
    );
    auto* zero_transition_fade_frames = static_cast<jint*>(
        env->GetPrimitiveArrayCritical(dynamic_effect_zero_transition_fade_frames, nullptr)
    );
    auto* zero_transition_start_gains = static_cast<jdouble*>(
        env->GetPrimitiveArrayCritical(dynamic_effect_zero_transition_start_gains, nullptr)
    );
    auto* phase_advance_frames = static_cast<jint*>(
        env->GetPrimitiveArrayCritical(dynamic_effect_phase_advance_frames, nullptr)
    );
    auto* restore_phase_offsets = static_cast<jdouble*>(
        env->GetPrimitiveArrayCritical(dynamic_effect_restore_phase_offsets, nullptr)
    );
    auto* dg = static_cast<jdouble*>(env->GetPrimitiveArrayCritical(dynamic_effect_gains, nullptr));
    auto* da = static_cast<jint*>(env->GetPrimitiveArrayCritical(dynamic_effect_active, nullptr));
    auto* sl = static_cast<jlong*>(env->GetPrimitiveArrayCritical(status_longs, nullptr));
    auto* sd = static_cast<jdouble*>(env->GetPrimitiveArrayCritical(status_doubles, nullptr));
    for (size_t i = 0; i < mixer->loops.size(); ++i) {
        mixer->loops[i].target_gain = lt[i];
        mixer->loops[i].increment = li[i];
    }
    for (size_t i = 0; i < mixer->effects.size(); ++i) {
        mixer->effects[i].target_gain = et[i];
        mixer->effects[i].increment = ei[i];
        if (trigger[i] < 0) {
            mixer->effects[i].active = false;
            mixer->effects[i].gain = 0.0;
            mixer->effects[i].target_gain = 0.0;
            mixer->effects[i].start_delay_frames = 0;
        } else if (trigger[i] > 0) {
            mixer->effects[i].phase = 0.0;
            mixer->effects[i].active = true;
            mixer->effects[i].has_looped = false;
            mixer->effects[i].start_delay_frames =
                std::clamp(effect_start_offset[i], 0, std::max(0, frames));
        }
    }
    for (size_t i = 0; i < mixer->dynamic_effects.size(); ++i) {
        auto& voice = mixer->dynamic_effects[i];
        voice.target_gain = dt[i];
        voice.increment = di[i];
        voice.zero_transition_active = zero_transition_active[i] != 0;
        voice.zero_transition_elapsed_frames =
            std::max(0, zero_transition_elapsed_frames[i]);
        voice.zero_transition_retain_frames =
            std::max(0, zero_transition_retain_frames[i]);
        voice.zero_transition_fade_frames =
            std::max(0, zero_transition_fade_frames[i]);
        voice.zero_transition_start_gain =
            std::max(0.0, zero_transition_start_gains[i]);
        voice.phase_advance_frames = std::max(0, phase_advance_frames[i]);
        if (voice.zero_transition_active) {
            voice.gain = zero_transition_gain(voice);
            voice.target_gain = 0.0;
        }
        if (command[i] < 0) {
            voice.active = false;
            voice.gain = 0.0;
            voice.target_gain = 0.0;
            voice.start_delay_frames = 0;
        } else if (command[i] > 0) {
            const size_t template_index = static_cast<size_t>(command[i] - 1);
            if (template_index < mixer->effects.size() &&
                !mixer->effects[template_index].looping) {
                const auto& source = mixer->effects[template_index];
                voice.clip = source.clip;
                voice.start = source.start;
                voice.end = source.end;
                voice.crossfade = 0;
                voice.looping = false;
                voice.active = true;
                voice.has_looped = false;
                voice.phase = std::clamp(
                    start_phase[i], 0.0, static_cast<double>(source.frame_count - 1)
                );
                voice.gain = std::max(0.0, start_gain[i]);
                voice.start_delay_frames = std::clamp(start_offset[i], 0, std::max(0, frames));
                voice.left = source.left;
                voice.right = source.right;
                voice.frame_count = source.frame_count;
                voice.loop_length = source.loop_length;
                voice.crossfade_start = source.crossfade_start;
                voice.resume = source.resume;
                voice.effective_loop_length = source.effective_loop_length;
            } else {
                voice.active = false;
                voice.gain = 0.0;
                voice.target_gain = 0.0;
                voice.start_delay_frames = 0;
            }
        }
        const double restore_phase_offset = restore_phase_offsets[i];
        if (voice.active && restore_phase_offset != 0.0 &&
            std::isfinite(restore_phase_offset) && std::abs(restore_phase_offset) <= 512.0) {
            voice.phase = std::clamp(
                voice.phase + restore_phase_offset,
                0.0,
                static_cast<double>(voice.frame_count - 1)
            );
        }
    }
    double peak = 0.0;
    for (int frame = 0; frame < frames; ++frame) {
        mixer->continuous += (target_continuous - mixer->continuous) * master_alpha;
        double loop_l = 0.0, loop_r = 0.0, effect_l = 0.0, effect_r = 0.0;
        for (size_t i = 0; i < mixer->loops.size(); ++i) {
            auto& voice = mixer->loops[i];
            voice.gain += (voice.target_gain - voice.gain) * layer_alpha;
            if (lr[i] != 0 && (voice.gain > 0.00001 || voice.target_gain > 0.00001)) {
                const StereoSample sample = cubic_voice_stereo(voice);
                loop_l += sample.left * voice.gain;
                loop_r += sample.right * voice.gain;
            }
            if (advance_voice(voice)) ++mixer->wraps;
        }
        for (size_t i = 0; i < mixer->effects.size(); ++i) {
            auto& voice = mixer->effects[i];
            if (voice.active && voice.start_delay_frames > 0) {
                --voice.start_delay_frames;
                continue;
            }
            voice.gain += (voice.target_gain - voice.gain) * layer_alpha;
            if (er[i] != 0 && voice.active && voice.gain > 0.00001) {
                const StereoSample sample = cubic_voice_stereo(voice);
                effect_l += sample.left * voice.gain;
                effect_r += sample.right * voice.gain;
            }
            if (advance_voice(voice)) ++mixer->wraps;
        }
        for (size_t i = 0; i < mixer->dynamic_effects.size(); ++i) {
            auto& voice = mixer->dynamic_effects[i];
            if (voice.active && voice.start_delay_frames > 0) {
                --voice.start_delay_frames;
                continue;
            }
            if (voice.zero_transition_active) {
                voice.gain = zero_transition_gain(voice);
            } else {
                voice.gain += (voice.target_gain - voice.gain) * layer_alpha;
            }
            if (voice.active && voice.gain > 0.0) {
                const StereoSample sample = cubic_voice_stereo(voice);
                effect_l += sample.left * voice.gain;
                effect_r += sample.right * voice.gain;
            }
            if (
                voice.active && voice.zero_transition_active &&
                voice.zero_transition_elapsed_frames < std::numeric_limits<int>::max()
            ) {
                ++voice.zero_transition_elapsed_frames;
            }
            if (voice.active && voice.phase_advance_frames > 0) {
                --voice.phase_advance_frames;
                advance_voice(voice);
            }
        }
        mixer->master += (target_master - mixer->master) * master_alpha;
        mixer->profile += (target_profile - mixer->profile) * profile_alpha;
        mixer->enabled += (target_enabled - mixer->enabled) * enabled_alpha;
        const double common = 0.65 * mixer->master * mixer->profile * mixer->enabled;
        const double pre_l = (loop_l * mixer->continuous + effect_l) * common;
        const double pre_r = (loop_r * mixer->continuous + effect_r) * common;
        if (std::abs(pre_l) > 1.0) ++mixer->over_range;
        if (std::abs(pre_r) > 1.0) ++mixer->over_range;
        const double left = limit_sample(pre_l), right = limit_sample(pre_r);
        peak = std::max(peak, std::max(std::abs(left), std::abs(right)));
        out[frame * 2] = static_cast<int16_t>(std::clamp(left * 32767.0, -32768.0, 32767.0));
        out[frame * 2 + 1] = static_cast<int16_t>(std::clamp(right * 32767.0, -32768.0, 32767.0));
    }
    mixer->frames += frames;
    for (size_t i = 0; i < mixer->loops.size(); ++i) lg[i] = mixer->loops[i].gain;
    for (size_t i = 0; i < mixer->effects.size(); ++i) {
        eg[i] = mixer->effects[i].gain;
        ea[i] = mixer->effects[i].active ? 1 : 0;
    }
    for (size_t i = 0; i < mixer->dynamic_effects.size(); ++i) {
        dg[i] = mixer->dynamic_effects[i].gain;
        da[i] = mixer->dynamic_effects[i].active ? 1 : 0;
    }
    sl[0] = mixer->frames; sl[1] = mixer->wraps; sl[2] = mixer->over_range; sd[0] = peak;
    env->ReleasePrimitiveArrayCritical(status_doubles, sd, 0);
    env->ReleasePrimitiveArrayCritical(status_longs, sl, 0);
    env->ReleasePrimitiveArrayCritical(dynamic_effect_active, da, 0);
    env->ReleasePrimitiveArrayCritical(dynamic_effect_gains, dg, 0);
    env->ReleasePrimitiveArrayCritical(
        dynamic_effect_restore_phase_offsets, restore_phase_offsets, JNI_ABORT
    );
    env->ReleasePrimitiveArrayCritical(
        dynamic_effect_phase_advance_frames, phase_advance_frames, JNI_ABORT
    );
    env->ReleasePrimitiveArrayCritical(
        dynamic_effect_zero_transition_start_gains, zero_transition_start_gains, JNI_ABORT
    );
    env->ReleasePrimitiveArrayCritical(
        dynamic_effect_zero_transition_fade_frames, zero_transition_fade_frames, JNI_ABORT
    );
    env->ReleasePrimitiveArrayCritical(
        dynamic_effect_zero_transition_retain_frames, zero_transition_retain_frames, JNI_ABORT
    );
    env->ReleasePrimitiveArrayCritical(
        dynamic_effect_zero_transition_elapsed_frames, zero_transition_elapsed_frames, JNI_ABORT
    );
    env->ReleasePrimitiveArrayCritical(
        dynamic_effect_zero_transition_active, zero_transition_active, JNI_ABORT
    );
    env->ReleasePrimitiveArrayCritical(dynamic_effect_start_gains, start_gain, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(dynamic_effect_start_phases, start_phase, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(dynamic_effect_start_offsets, start_offset, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(dynamic_effect_commands, command, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(dynamic_effect_increments, di, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(dynamic_effect_targets, dt, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(effect_active, ea, 0);
    env->ReleasePrimitiveArrayCritical(effect_gains, eg, 0);
    env->ReleasePrimitiveArrayCritical(effect_real, er, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(effect_start_offsets, effect_start_offset, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(effect_triggers, trigger, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(effect_increments, ei, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(effect_targets, et, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(loop_gains, lg, 0);
    env->ReleasePrimitiveArrayCritical(loop_real, lr, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(loop_increments, li, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(loop_targets, lt, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(output, out, 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_gabrielpc_enginesoundsimulator_audio_NativePcmMixer_00024Companion_nativeRelease(
    JNIEnv*, jobject, jlong handle
) {
    delete reinterpret_cast<NativeMixer*>(handle);
}
