#include <jni.h>

#include <android/log.h>
#include <fmod.hpp>
#include <fmod_studio.hpp>

#include <algorithm>
#include <atomic>
#include <array>
#include <chrono>
#include <cctype>
#include <cmath>
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <deque>
#include <fstream>
#include <iomanip>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

namespace {

constexpr char kLogTag[] = "FmodBankRuntime";
constexpr int kFmodOutputRate = 48000;
constexpr unsigned int kFmodDspBlockSize = 256;
constexpr int kFmodDspBlocks = 4;
constexpr int kFmodLogicalChannelCap = 2048;
constexpr int kFmodRealChannelCap = 256;
constexpr int kPerspectiveExterior = 1;
// Intentional FMOD parity choice: the Lab keeps these authored event inputs at
// full load. The physical pedal still controls drivetrain simulation, but it
// must not attenuate or swap the bank's load layers.
constexpr float kFullLoadAudioThrottle = 1.0f;
// Backfire is authored as a lift-off one-shot: its throttle automation fades it to roughly
// -38 dB above the 0.75 point. Keep the continuous engine at full-load, but feed this event the
// low-throttle endpoint so a triggered backfire is actually audible instead of merely appearing
// as a virtual 0% card. This is an event-specific FMOD parameter, not a change to pedal physics.
constexpr float kBackfireAudioThrottle = 0.0f;
constexpr double kRecentSourceSeconds = 1.5;
constexpr char kFieldSeparator = '\x1f';
constexpr char kStableIdSeparator = '\x1e';
constexpr std::size_t kDiagnosticRingCapacity = 8192;
constexpr std::size_t kDiagnosticEventNameCapacity = 64;
constexpr std::size_t kDiagnosticPathCapacity = 320;
constexpr std::size_t kDiagnosticSourceCapacity = 320;

constexpr std::array<const char*, 16> kAllowedEventNames = {
    "engine_int",
    "engine_ext",
    "transmission",
    "transmission_ext",
    "turbo",
    "limiter",
    "gear_int",
    "gear_ext",
    "backfire_int",
    "backfire_ext",
    "tractioncontrol_int",
    "tractioncontrol_ext",
    "gear_grind",
    "start",
    "wind",
    "tyres",
};

// Wind and tyres may appear in the banks, but are deliberately excluded from
// the runtime. Keeping their names in the discovery allow-list lets the bank
// audit distinguish an intentionally excluded event from a missing event.
bool isPlayableEventName(const std::string& name) {
    return name != "wind" && name != "tyres";
}

bool isAllowedEventName(const std::string& name) {
    return std::find_if(
        kAllowedEventNames.begin(),
        kAllowedEventNames.end(),
        [&name](const char* candidate) { return name == candidate; }
    ) != kAllowedEventNames.end();
}

std::string resultText(FMOD_RESULT result, const std::string& operation) {
    std::ostringstream output;
    output << operation << " failed with FMOD_RESULT " << static_cast<int>(result);
    return output.str();
}

std::string lowercase(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char character) {
        return static_cast<char>(std::tolower(character));
    });
    return value;
}

std::string trimWhitespace(std::string value) {
    const auto first = std::find_if_not(value.begin(), value.end(), [](unsigned char character) {
        return std::isspace(character);
    });
    const auto last = std::find_if_not(value.rbegin(), value.rend(), [](unsigned char character) {
        return std::isspace(character);
    }).base();
    if (first >= last) {
        return {};
    }

    return std::string(first, last);
}

std::string eventSuffix(const std::string& path) {
    const std::string normalized = trimWhitespace(path);
    const std::size_t slash = normalized.find_last_of('/');
    return lowercase(normalized.substr(slash == std::string::npos ? 0 : slash + 1));
}

std::string stableSourceId(const std::string& eventPath, const std::string& soundName) {
    return eventPath + kStableIdSeparator + soundName;
}

std::string formatGuid(const FMOD_GUID& guid) {
    char buffer[40]{};
    std::snprintf(
        buffer,
        sizeof(buffer),
        "{%08x-%04x-%04x-%02x%02x-%02x%02x%02x%02x%02x%02x}",
        guid.Data1,
        guid.Data2,
        guid.Data3,
        guid.Data4[0],
        guid.Data4[1],
        guid.Data4[2],
        guid.Data4[3],
        guid.Data4[4],
        guid.Data4[5],
        guid.Data4[6],
        guid.Data4[7]
    );
    return buffer;
}

std::string runtimeClassificationForEvent(const std::string& name) {
    if (!isAllowedEventName(name)) return "unsupported";
    if (!isPlayableEventName(name)) return "excluded_by_policy";
    if (name == "engine_int" || name == "engine_ext" ||
        name == "transmission" || name == "transmission_ext") {
        return "continuous";
    }
    if (name == "turbo") return "continuous_if_physics_has_turbo";
    if (name == "start") return "startup_once";
    if (name == "limiter") return "decay_controlled";
    return "pulsed";
}

double monotonicSeconds() {
    return std::chrono::duration<double>(
        std::chrono::steady_clock::now().time_since_epoch()
    ).count();
}

FMOD_3D_ATTRIBUTES attributesAt(float x, float y, float z) {
    FMOD_3D_ATTRIBUTES attributes{};
    attributes.position = {x, y, z};
    attributes.velocity = {0.0f, 0.0f, 0.0f};
    attributes.forward = {0.0f, 0.0f, -1.0f};
    attributes.up = {0.0f, 1.0f, 0.0f};
    return attributes;
}

FMOD_RESULT F_CALL passthroughRead(
    FMOD_DSP_STATE*,
    float* inbuffer,
    float* outbuffer,
    unsigned int length,
    int inchannels,
    int* outchannels
) {
    if (inbuffer != nullptr && outbuffer != nullptr && inchannels > 0) {
        std::memcpy(outbuffer, inbuffer, length * static_cast<unsigned int>(inchannels) * sizeof(float));
    }
    if (outchannels != nullptr) {
        *outchannels = inchannels;
    }
    return FMOD_OK;
}

FMOD_RESULT F_CALL acceptDistanceFilterAttributes(FMOD_DSP_STATE*, int, void*, unsigned int) {
    return FMOD_OK;
}

FMOD_DSP_DESCRIPTION createDistanceFilterDescriptor() {
    static FMOD_DSP_PARAMETER_DESC maxDistance{};
    static FMOD_DSP_PARAMETER_DESC frequency{};
    static FMOD_DSP_PARAMETER_DESC attributes{};
    static FMOD_DSP_PARAMETER_DESC* parameters[] = {&maxDistance, &frequency, &attributes};
    static bool initialized = false;
    if (!initialized) {
        FMOD_DSP_INIT_PARAMDESC_FLOAT(
            maxDistance,
            "Max Dist",
            "m",
            "Distance at which the filter reaches its target frequency.",
            0.0f,
            10000.0f,
            100.0f
        );
        FMOD_DSP_INIT_PARAMDESC_FLOAT(
            frequency,
            "Frequency",
            "Hz",
            "Low-pass target frequency at maximum distance.",
            10.0f,
            22000.0f,
            1000.0f
        );
        FMOD_DSP_INIT_PARAMDESC_DATA(
            attributes,
            "3D Attributes",
            "",
            "Source and listener transforms supplied by FMOD.",
            FMOD_DSP_PARAMETER_DATA_TYPE_3DATTRIBUTES
        );
        initialized = true;
    }

    FMOD_DSP_DESCRIPTION description{};
    description.pluginsdkversion = FMOD_PLUGIN_SDK_VERSION;
    std::strncpy(description.name, "FMOD Distance Filter", sizeof(description.name) - 1);
    description.version = 0x00010000;
    description.numinputbuffers = 1;
    description.numoutputbuffers = 1;
    description.read = passthroughRead;
    description.numparameters = 3;
    description.paramdesc = parameters;
    description.setparameterdata = acceptDistanceFilterAttributes;
    return description;
}

struct GainState {
    float decibels = 0.0f;
    bool inverted = false;
};

FMOD_RESULT F_CALL createGain(FMOD_DSP_STATE* state) {
    if (state == nullptr) {
        return FMOD_ERR_INVALID_PARAM;
    }
    state->plugindata = new GainState();
    return FMOD_OK;
}

FMOD_RESULT F_CALL releaseGain(FMOD_DSP_STATE* state) {
    if (state != nullptr) {
        delete static_cast<GainState*>(state->plugindata);
        state->plugindata = nullptr;
    }
    return FMOD_OK;
}

FMOD_RESULT F_CALL setGainDecibels(FMOD_DSP_STATE* state, int, float value) {
    if (state != nullptr && state->plugindata != nullptr) {
        static_cast<GainState*>(state->plugindata)->decibels = value;
    }
    return FMOD_OK;
}

FMOD_RESULT F_CALL setGainInverted(FMOD_DSP_STATE* state, int, FMOD_BOOL value) {
    if (state != nullptr && state->plugindata != nullptr) {
        static_cast<GainState*>(state->plugindata)->inverted = value != 0;
    }
    return FMOD_OK;
}

FMOD_RESULT F_CALL applyGain(
    FMOD_DSP_STATE* state,
    float* inbuffer,
    float* outbuffer,
    unsigned int length,
    int inchannels,
    int* outchannels
) {
    const int channels = std::max(0, inchannels);
    if (outchannels != nullptr) {
        *outchannels = channels;
    }
    if (inbuffer == nullptr || outbuffer == nullptr || channels == 0) {
        return FMOD_OK;
    }

    const auto* gain = state == nullptr ? nullptr : static_cast<const GainState*>(state->plugindata);
    const float decibels = gain == nullptr ? 0.0f : gain->decibels;
    const float polarity = gain != nullptr && gain->inverted ? -1.0f : 1.0f;
    const float scale = std::pow(10.0f, decibels / 20.0f) * polarity;
    const unsigned int samples = length * static_cast<unsigned int>(channels);
    if (std::abs(scale - 1.0f) < 0.000001f) {
        std::memcpy(outbuffer, inbuffer, samples * sizeof(float));
    } else {
        for (unsigned int index = 0; index < samples; ++index) {
            outbuffer[index] = inbuffer[index] * scale;
        }
    }
    return FMOD_OK;
}

FMOD_DSP_DESCRIPTION createGainDescriptor() {
    static FMOD_DSP_PARAMETER_DESC gain{};
    static FMOD_DSP_PARAMETER_DESC invert{};
    static FMOD_DSP_PARAMETER_DESC* parameters[] = {&gain, &invert};
    static bool initialized = false;
    if (!initialized) {
        FMOD_DSP_INIT_PARAMDESC_FLOAT(
            gain,
            "Gain",
            "dB",
            "Linear output gain in decibels.",
            -80.0f,
            10.0f,
            0.0f
        );
        FMOD_DSP_INIT_PARAMDESC_BOOL(
            invert,
            "Invert",
            "",
            "Invert the output polarity.",
            false,
            nullptr
        );
        initialized = true;
    }

    FMOD_DSP_DESCRIPTION description{};
    description.pluginsdkversion = FMOD_PLUGIN_SDK_VERSION;
    std::strncpy(description.name, "FMOD Gain", sizeof(description.name) - 1);
    description.version = 0x00010000;
    description.numinputbuffers = 1;
    description.numoutputbuffers = 1;
    description.create = createGain;
    description.release = releaseGain;
    description.read = applyGain;
    description.numparameters = 2;
    description.paramdesc = parameters;
    description.setparameterfloat = setGainDecibels;
    description.setparameterbool = setGainInverted;
    return description;
}

bool parseGuid(const std::string& text, FMOD_GUID* output) {
    if (output == nullptr) {
        return false;
    }

    unsigned int data1 = 0;
    unsigned int data2 = 0;
    unsigned int data3 = 0;
    unsigned int data4[8]{};
    const int matched = std::sscanf(
        text.c_str(),
        "{%8x-%4x-%4x-%2x%2x-%2x%2x%2x%2x%2x%2x}",
        &data1,
        &data2,
        &data3,
        &data4[0],
        &data4[1],
        &data4[2],
        &data4[3],
        &data4[4],
        &data4[5],
        &data4[6],
        &data4[7]
    );
    if (matched != 11) {
        return false;
    }

    output->Data1 = data1;
    output->Data2 = static_cast<unsigned short>(data2);
    output->Data3 = static_cast<unsigned short>(data3);
    for (int index = 0; index < 8; ++index) {
        output->Data4[index] = static_cast<unsigned char>(data4[index]);
    }
    return true;
}

class FmodRuntime;

struct EventSlot {
    FmodRuntime* runtime = nullptr;
    std::string name;
    std::string path;
    FMOD::Studio::EventDescription* description = nullptr;
    FMOD::Studio::EventInstance* instance = nullptr;
};

struct RecentSource {
    std::string eventPath;
    std::string eventName;
    std::string soundName;
    unsigned int sampleLengthMs = 0;
    int sampleChannels = 0;
    float sampleRateHz = 0.0f;
    double lastSeenSeconds = 0.0;
    int callbackVoiceCount = 0;
    // FMOD's sound callbacks expose the source, not a Core channel handle. A monotonically
    // assigned serial lets debug traces follow each callback-created voice without changing the
    // production source aggregation used by the mixer.
    std::deque<std::uint64_t> activeVoiceSerials;
    std::uint64_t latestVoiceSerial = 0;
};

struct VoiceAggregate {
    std::string id;
    std::string eventPath;
    std::string eventName;
    std::string soundName;
    unsigned int sampleLengthMs = 0;
    int sampleChannels = 0;
    float sampleRateHz = 0.0f;
    float audibilitySquared = 0.0f;
    float routeGain = 0.0f;
    int voiceCount = 0;
    int virtualVoiceCount = 0;
    bool callbackActive = false;
};

enum class NativeDiagnosticKind : std::uint8_t {
    EventStart,
    EventStop,
    VoicePlayed,
    VoiceStopped,
    VoiceState,
};

/**
 * Callback sound metadata disambiguates banks that reuse a raw filename in multiple authored
 * instruments. It is captured only in explicit debug mode; the offline inventory must still
 * reject a non-unique event/name/format/duration join rather than guessing an instrument.
 */
struct SoundMetadata {
    unsigned int lengthMs = 0;
    int channels = 0;
    float rateHz = 0.0f;
};

struct NativeDiagnosticRecord {
    std::uint64_t sequence = 0;
    double timestampSeconds = 0.0;
    std::uint64_t simulationFrameId = 0;
    std::uint64_t voiceSerial = 0;
    double durationSeconds = -1.0;
    int kind = 0;
    int result = 0;
    int gear = 0;
    int voiceCount = 0;
    int virtualVoiceCount = 0;
    int callbackVoiceCount = 0;
    float audibility = 0.0f;
    float routeGain = 0.0f;
    float rpm = 0.0f;
    float drivetrainSpeed = 0.0f;
    float throttle = 0.0f;
    float boostNormalized = 0.0f;
    float boostAbsolute = 0.0f;
    float bov = 0.0f;
    float bovDecay = 0.0f;
    float shiftProgress = 0.0f;
    std::uint64_t shiftSerial = 0;
    int stateFlags = 0;
    bool shifting = false;
    unsigned int sampleLengthMs = 0;
    int sampleChannels = 0;
    float sampleRateHz = 0.0f;
    std::array<char, kDiagnosticEventNameCapacity> eventName{};
    std::array<char, kDiagnosticPathCapacity> eventPath{};
    std::array<char, kDiagnosticSourceCapacity> sourceName{};
};

struct NativeDiagnosticRing {
    std::array<NativeDiagnosticRecord, kDiagnosticRingCapacity> records{};
    std::uint64_t writeSequence = 0;
    std::uint64_t readSequence = 0;
};

struct BankEventCatalogEntry {
    std::string path;
    std::string guid;
    std::string suffix;
    std::string classification;
};

template <std::size_t Capacity>
void copyDiagnosticText(std::array<char, Capacity>* destination, const std::string& source) {
    if (destination == nullptr) return;
    destination->fill('\0');
    const std::size_t copied = std::min(source.size(), Capacity - 1);
    if (copied > 0) {
        std::memcpy(destination->data(), source.data(), copied);
    }
}

class FmodRuntime {
public:
    std::string open(
        const std::string& commonStringsBankPath,
        const std::string& commonBankPath,
        const std::string& carBankPath,
        const std::string& alfaBackfireDirectory,
        int perspective,
        bool hasTurbo,
        float idleRpm,
        const std::array<float, 12>& spatial,
        bool diagnosticsEnabled
    ) {
        std::lock_guard<std::mutex> lock(mutex_);
        closeLocked();
        setDiagnosticsEnabledLocked(diagnosticsEnabled);

        FMOD_RESULT result = FMOD::Studio::System::create(&studio_);
        if (result != FMOD_OK) {
            return resultText(result, "Studio::System::create");
        }

        result = studio_->getCoreSystem(&core_);
        if (result != FMOD_OK) {
            return failAndCloseLocked(resultText(result, "getCoreSystem"));
        }
        result = core_->setSoftwareFormat(kFmodOutputRate, FMOD_SPEAKERMODE_STEREO, 0);
        if (result != FMOD_OK) {
            return failAndCloseLocked(resultText(result, "setSoftwareFormat"));
        }
        result = core_->setSoftwareChannels(kFmodRealChannelCap);
        if (result != FMOD_OK) {
            return failAndCloseLocked(resultText(result, "setSoftwareChannels"));
        }
        result = core_->setDSPBufferSize(kFmodDspBlockSize, kFmodDspBlocks);
        if (result != FMOD_OK) {
            return failAndCloseLocked(resultText(result, "setDSPBufferSize"));
        }
        result = studio_->initialize(
            kFmodLogicalChannelCap,
            FMOD_STUDIO_INIT_SYNCHRONOUS_UPDATE,
            FMOD_INIT_NORMAL,
            nullptr
        );
        if (result != FMOD_OK) {
            return failAndCloseLocked(resultText(result, "Studio::System::initialize"));
        }

        loadAlfaBackfireSamplesLocked(alfaBackfireDirectory);
        loadShiftSamplesLocked(alfaBackfireDirectory);

        distanceFilter_ = createDistanceFilterDescriptor();
        result = studio_->registerPlugin(&distanceFilter_);
        if (result != FMOD_OK) {
            return failAndCloseLocked(resultText(result, "register FMOD Distance Filter"));
        }
        gain_ = createGainDescriptor();
        result = studio_->registerPlugin(&gain_);
        if (result != FMOD_OK) {
            return failAndCloseLocked(resultText(result, "register FMOD Gain"));
        }

        result = studio_->loadBankFile(
            commonStringsBankPath.c_str(),
            FMOD_STUDIO_LOAD_BANK_NORMAL,
            &commonStringsBank_
        );
        if (result != FMOD_OK) {
            return failAndCloseLocked(resultText(result, "load common.strings.bank"));
        }
        result = studio_->loadBankFile(commonBankPath.c_str(), FMOD_STUDIO_LOAD_BANK_NORMAL, &commonBank_);
        if (result != FMOD_OK) {
            return failAndCloseLocked(resultText(result, "load common.bank"));
        }
        result = studio_->loadBankFile(carBankPath.c_str(), FMOD_STUDIO_LOAD_BANK_NORMAL, &bank_);
        if (result != FMOD_OK) {
            return failAndCloseLocked(resultText(result, "load car bank"));
        }

        discoverEventsLocked(carBankPath);
        if (events_.find("engine_int") == events_.end() || events_.find("engine_ext") == events_.end()) {
            return failAndCloseLocked("The installed bank has no engine_int/engine_ext event pair.");
        }
        for (const auto& event : events_) {
            if (!isPlayableEventName(event.first)) {
                continue;
            }
            if (createEventSlotLocked(event.first, event.second) == nullptr) {
                return failAndCloseLocked(lastError_);
            }
        }

        engineAttributes_ = attributesAt(spatial[0], spatial[1], spatial[2]);
        backfireAttributes_ = attributesAt(spatial[3], spatial[4], spatial[5]);
        cabinListenerAttributes_ = attributesAt(spatial[6], spatial[7], spatial[8]);
        exteriorListenerAttributes_ = attributesAt(spatial[9], spatial[10], spatial[11]);
        applySpatialAttributesLocked();

        perspective_ = perspective;
        hasTurbo_ = hasTurbo;
        idleRpm_ = std::max(1.0f, idleRpm);
        setListenerLocked();
        initializeParametersLocked();
        startSelectedContinuousEventsLocked();
        startEventLocked("start");
        result = studio_->update();
        if (result != FMOD_OK) {
            return failAndCloseLocked(resultText(result, "initial Studio::System::update"));
        }
        active_ = true;
        return {};
    }

    std::string update(
        float dt,
        float rpm,
        float drivetrainSpeed,
        float throttle,
        int perspective,
        float boost,
        float boostAbsolute,
        float bov,
        float bovDecay,
        int gear,
        bool isShifting,
        float shiftProgress,
        std::uint64_t shiftSerial,
        int limiterPulseCount,
        int shiftStartedCount,
        int shiftDirection,
        int shiftRejectedCount,
        int backfirePulseCount,
        int backfireSampleIndex,
        bool tractionActive,
        int tractionPulseCount,
        std::uint64_t simulationFrameId
    ) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!active_ || studio_ == nullptr) {
            return "FMOD runtime is not active.";
        }

        const float cleanDt = std::clamp(dt, 0.0001f, 0.1f);
        const float cleanRpm = std::max(1.0f, rpm);
        const float cleanThrottle = std::clamp(throttle, 0.0f, 1.0f);
        updateTraceContextLocked(
            cleanRpm,
            drivetrainSpeed,
            cleanThrottle,
            boost,
            boostAbsolute,
            bov,
            bovDecay,
            gear,
            isShifting,
            shiftProgress,
            shiftSerial,
            shiftStartedCount > 0,
            shiftDirection,
            limiterPulseCount > 0,
            backfirePulseCount > 0,
            tractionActive,
            tractionPulseCount > 0,
            simulationFrameId
        );
        // Intentional lift-off policy: the authored engine event keeps its full-load
        // input while the drivetrain RPM falls naturally. This preserves the same
        // LOAD/COAST layer balance on deceleration as on acceleration; the pedal
        // still affects physics, but it is not allowed to mute or remap FMOD layers.
        (void)cleanThrottle;
        if (perspective != perspective_) {
            switchPerspectiveLocked(perspective);
        }

        for (const char* name : {"engine_int", "engine_ext"}) {
            setParameterQuietly(slotInstance(name), "rpms", cleanRpm);
        }
        for (const char* name : {"transmission", "transmission_ext"}) {
            setParameterQuietly(slotInstance(name), "drivetrain_speed", drivetrainSpeed);
        }

        EventSlot* turbo = slot("turbo");
        if (turbo != nullptr && hasTurbo_) {
            setParameterQuietly(turbo->instance, "boost", std::max(0.0f, boost));
            setParameterQuietly(turbo->instance, "bov", std::max(0.0f, bov));
            setParameterQuietly(turbo->instance, "bov_decay", std::max(0.0f, bovDecay));
        }

        limiterDecay_ += cleanDt;
        if (limiterPulseCount > 0) {
            limiterDecay_ = 0.0f;
        }
        EventSlot* limiter = slot("limiter");
        if (limiter != nullptr) {
            setParameterQuietly(limiter->instance, "decay", limiterDecay_);
            if (limiterDecay_ <= 10.0f && !limiterRunning_) {
                startEventLocked("limiter");
                limiterRunning_ = true;
            } else if (limiterDecay_ > 10.0f && limiterRunning_) {
                stopEventLocked("limiter", FMOD_STUDIO_STOP_ALLOWFADEOUT);
                limiterRunning_ = false;
            }
        }

        if (shiftStartedCount > 0 && shiftDirection != 0) {
            const std::string selected = perspective_ == kPerspectiveExterior
                ? "gear_ext"
                : "gear_int";
            for (int pulse = 0; pulse < shiftStartedCount; ++pulse) {
                if (!shiftSoundEnabled_) continue;
                if (shiftSoundOverride_) {
                    playShiftSampleLocked(shiftDirection > 0);
                    continue;
                }
                if (slot(selected) != nullptr && !isPlayingLocked(slotInstance(selected))) {
                    stopEventLocked(selected, FMOD_STUDIO_STOP_ALLOWFADEOUT);
                    setParameterQuietly(slotInstance(selected), "state", shiftDirection > 0 ? 1.0f : 0.0f);
                    startEventLocked(selected);
                }
            }
        }
        for (int pulse = 0; pulse < shiftRejectedCount; ++pulse) {
            if (!isPlayingLocked(slotInstance("gear_grind"))) {
                startEventLocked("gear_grind");
            }
        }

        for (int pulse = 0; pulse < backfirePulseCount; ++pulse) {
            if (!backfireAudioEnabled_) continue;
            if (!eitherBackfirePlayingLocked()) {
                const std::string selected = perspectiveEventLocked("backfire_int", "backfire_ext");
                if (!backfireUseOriginal_ && backfireSampleIndex >= 1 && alfaBackfireSamplesLoaded_) {
                    playAlfaBackfireSampleLocked(backfireSampleIndex);
                } else {
                    // A disabled global policy means pure bank behavior: the app only sends the
                    // lift-off edge and lets the authored FMOD event choose its own sources.
                    startEventLocked(selected);
                }
            }
        }

        (void)tractionActive;
        (void)tractionPulseCount;
        // Intentional audio policy: drivetrain traction limiting remains part of
        // the physics, but its authored sound is disabled because this simulator
        // should not announce the internal correction as a driver-event effect.
        tractionDecay_ = 10.0f;
        for (const char* name : {"tractioncontrol_int", "tractioncontrol_ext"}) {
            setParameterQuietly(slotInstance(name), "decay", tractionDecay_);
            if (isPlayingLocked(slotInstance(name))) {
                stopEventLocked(name, FMOD_STUDIO_STOP_IMMEDIATE);
            }
        }

        const FMOD_RESULT result = studio_->update();
        if (result != FMOD_OK) {
            return resultText(result, "Studio::System::update");
        }
        return {};
    }

    void setHostGains(float engineGain, float effectsGain) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!active_) return;
        const float engine = std::max(0.0f, engineGain);
        const float effects = std::max(0.0f, effectsGain);
        if (engine == hostEngineGain_ && effects == hostEffectsGain_) return;
        hostEngineGain_ = engine;
        hostEffectsGain_ = effects;
        if (alfaBackfireChannel_ != nullptr) alfaBackfireChannel_->setVolume(hostEffectsGain_ * backfireGain_);
        applyEventOverridesLocked();
    }

    void setCategoryGains(float transmissionGain, float gearShiftGain, float turboGain, float backfireGain) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!active_) return;
        const float transmission = std::max(0.0f, transmissionGain);
        const float gearShift = std::max(0.0f, gearShiftGain);
        const float turbo = std::max(0.0f, turboGain);
        const float backfire = std::max(0.0f, backfireGain);
        if (
            transmission == transmissionGain_ &&
            gearShift == gearShiftGain_ &&
            turbo == turboGain_ &&
            backfire == backfireGain_
        ) return;
        transmissionGain_ = transmission;
        gearShiftGain_ = gearShift;
        turboGain_ = turbo;
        backfireGain_ = backfire;
        if (alfaBackfireChannel_ != nullptr) alfaBackfireChannel_->setVolume(hostEffectsGain_ * backfireGain_);
        applyEventOverridesLocked();
    }

    void setBackfireOnly(bool enabled) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!active_) return;
        if (backfireOnly_ == enabled) return;
        backfireOnly_ = enabled;
        applyEventOverridesLocked();
    }

    void setBackfireAudioEnabled(bool enabled) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (backfireAudioEnabled_ == enabled) return;
        backfireAudioEnabled_ = enabled;
        if (!backfireAudioEnabled_ && alfaBackfireChannel_ != nullptr) {
            alfaBackfireChannel_->stop();
            alfaBackfireChannel_ = nullptr;
        }
        applyEventOverridesLocked();
    }

    void setBackfireAllowedSamples(int mask) {
        std::lock_guard<std::mutex> lock(mutex_);
        backfireAllowedSamplesMask_ = mask & 0x0F;
        if (backfireAllowedSamplesMask_ == 0) backfireAllowedSamplesMask_ = 1;
    }

    void setShiftSoundOverride(bool enabled) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!active_ || shiftSoundOverride_ == enabled) return;
        shiftSoundOverride_ = enabled;
        applyEventOverridesLocked();
    }

    void setShiftSoundEnabled(bool enabled) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (shiftSoundEnabled_ == enabled) return;
        shiftSoundEnabled_ = enabled;
        applyEventOverridesLocked();
    }

    void setShiftOverrideGain(float gain) {
        std::lock_guard<std::mutex> lock(mutex_);
        shiftOverrideGain_ = std::max(0.0f, gain);
    }

    void setTransmissionAudioEnabled(bool enabled) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (transmissionAudioEnabled_ == enabled) return;
        transmissionAudioEnabled_ = enabled;
        applyEventOverridesLocked();
    }

    void setTurboAudioEnabled(bool enabled) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (turboAudioEnabled_ == enabled) return;
        turboAudioEnabled_ = enabled;
        applyEventOverridesLocked();
    }

    void setBackfireUseOriginal(bool enabled) {
        std::lock_guard<std::mutex> lock(mutex_);
        backfireUseOriginal_ = enabled;
    }

    void setExteriorPureAudio(bool enabled) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!active_ || exteriorPureAudio_ == enabled) return;
        exteriorPureAudio_ = enabled;
        applySpatialAttributesLocked();
        setListenerLocked();
    }

    void setEventOverrides(
        const std::vector<std::string>& mutedEvents,
        const std::vector<std::string>& soloEvents
    ) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!active_) return;
        mutedEvents_.clear();
        soloEvents_.clear();
        for (const std::string& name : mutedEvents) mutedEvents_[name] = true;
        for (const std::string& name : soloEvents) soloEvents_[name] = true;
        applyEventOverridesLocked();
    }

    std::vector<std::string> voiceSnapshots() {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!active_) {
            return {};
        }

        std::unordered_map<std::string, VoiceAggregate> aggregates;
        collectVoiceSnapshotsLocked(&aggregates);
        mergeRecentSourcesLocked(&aggregates);

        std::vector<VoiceAggregate> ordered;
        ordered.reserve(aggregates.size());
        for (auto& pair : aggregates) {
            ordered.push_back(std::move(pair.second));
        }
        std::sort(ordered.begin(), ordered.end(), [](const VoiceAggregate& left, const VoiceAggregate& right) {
            const bool leftActive = left.voiceCount > 0 || left.callbackActive;
            const bool rightActive = right.voiceCount > 0 || right.callbackActive;
            if (leftActive != rightActive) {
                return leftActive > rightActive;
            }
            if (left.eventPath != right.eventPath) {
                return left.eventPath < right.eventPath;
            }
            return left.soundName < right.soundName;
        });

        std::vector<std::string> rows;
        rows.reserve(ordered.size());
        for (const VoiceAggregate& source : ordered) {
            const float audibility = std::clamp(std::sqrt(source.audibilitySquared), 0.0f, 1.0f);
            const bool active = source.voiceCount > 0 || source.callbackActive;
            const bool virtualOnly = source.voiceCount > 0 && source.virtualVoiceCount == source.voiceCount;
            recordVoiceState(source, audibility);
            std::ostringstream row;
            row << source.id << kFieldSeparator
                << source.eventPath << kFieldSeparator
                << source.eventName << kFieldSeparator
                << source.soundName << kFieldSeparator
                << audibility << kFieldSeparator
                << source.routeGain << kFieldSeparator
                << source.voiceCount << kFieldSeparator
                << (virtualOnly ? 1 : 0) << kFieldSeparator
                << (active ? 1 : 0);
            rows.push_back(row.str());
        }
        return rows;
    }

    void setDiagnosticsEnabled(bool enabled) {
        std::lock_guard<std::mutex> lock(mutex_);
        setDiagnosticsEnabledLocked(enabled);
    }

    std::vector<std::string> diagnosticRecords() {
        std::lock_guard<std::mutex> lock(mutex_);
        return drainDiagnosticRecordsLocked();
    }

    std::vector<std::string> eventCatalog() {
        std::lock_guard<std::mutex> lock(mutex_);
        std::vector<std::string> rows;
        rows.reserve(eventCatalog_.size());
        for (const BankEventCatalogEntry& event : eventCatalog_) {
            rows.push_back(
                std::string("BANK_EVENT_CATALOG") + kFieldSeparator + event.path +
                kFieldSeparator + event.guid + kFieldSeparator + event.suffix +
                kFieldSeparator + event.classification
            );
        }
        return rows;
    }

    void close() {
        std::lock_guard<std::mutex> lock(mutex_);
        closeLocked();
    }

    void onSoundCallback(EventSlot& event, FMOD_STUDIO_EVENT_CALLBACK_TYPE type, FMOD::Sound* sound) {
        if (sound == nullptr) {
            return;
        }
        char name[512]{};
        if (sound->getName(name, sizeof(name)) != FMOD_OK || name[0] == '\0') {
            std::strncpy(name, "<unnamed sound>", sizeof(name) - 1);
        }
        const std::string id = stableSourceId(event.path, name);
        std::lock_guard<std::mutex> callbackLock(callbackMutex_);
        RecentSource& recent = recentSources_[id];
        recent.eventPath = event.path;
        recent.eventName = event.name;
        recent.soundName = name;
        const double callbackTime = monotonicSeconds();
        recent.lastSeenSeconds = callbackTime;
        std::uint64_t voiceSerial = 0;
        double voiceDuration = -1.0;
        const bool diagnosticsEnabled = diagnosticsEnabled_.load(std::memory_order_relaxed);
        SoundMetadata sourceMetadata{};
        if (diagnosticsEnabled) {
            sourceMetadata = inspectSoundMetadata(sound);
            recent.sampleLengthMs = sourceMetadata.lengthMs;
            recent.sampleChannels = sourceMetadata.channels;
            recent.sampleRateHz = sourceMetadata.rateHz;
        }
        if (type == FMOD_STUDIO_EVENT_CALLBACK_SOUND_PLAYED) {
            recent.callbackVoiceCount = std::min(recent.callbackVoiceCount + 1, 32767);
            if (diagnosticsEnabled) {
                voiceSerial = nextVoiceSerial_++;
                recent.activeVoiceSerials.push_back(voiceSerial);
                recent.latestVoiceSerial = voiceSerial;
                voiceStartTimes_[voiceSerial] = callbackTime;
            }
        } else if (type == FMOD_STUDIO_EVENT_CALLBACK_SOUND_STOPPED) {
            recent.callbackVoiceCount = std::max(0, recent.callbackVoiceCount - 1);
            if (diagnosticsEnabled && !recent.activeVoiceSerials.empty()) {
                voiceSerial = recent.activeVoiceSerials.front();
                recent.activeVoiceSerials.pop_front();
                const auto start = voiceStartTimes_.find(voiceSerial);
                if (start != voiceStartTimes_.end()) {
                    voiceDuration = std::max(0.0, callbackTime - start->second);
                    voiceStartTimes_.erase(start);
                }
                if (recent.activeVoiceSerials.empty()) {
                    recent.latestVoiceSerial = 0;
                }
            } else if (diagnosticsEnabled && recent.latestVoiceSerial != 0) {
                // Some FMOD backends can deliver a stop callback after the source aggregate has
                // been refreshed without exposing the matching play in the same callback batch.
                // Keep the diagnostic link useful without changing FMOD's lifecycle: fall back
                // to the most recently observed serial and report an unknown duration if needed.
                voiceSerial = recent.latestVoiceSerial;
                const auto start = voiceStartTimes_.find(voiceSerial);
                if (start != voiceStartTimes_.end()) {
                    voiceDuration = std::max(0.0, callbackTime - start->second);
                    voiceStartTimes_.erase(start);
                }
                recent.latestVoiceSerial = 0;
            }
        }
        if (diagnosticsEnabled) {
            recordDiagnostic(
                type == FMOD_STUDIO_EVENT_CALLBACK_SOUND_PLAYED
                    ? NativeDiagnosticKind::VoicePlayed
                    : NativeDiagnosticKind::VoiceStopped,
                callbackTime,
                &event,
                name,
                FMOD_OK,
                voiceSerial,
                voiceDuration,
                0.0f,
                0.0f,
                0,
                0,
                recent.callbackVoiceCount,
                sourceMetadata
            );
        }
    }

private:
    void loadAlfaBackfireSamplesLocked(const std::string& directory) {
        if (core_ == nullptr || directory.empty()) return;
        bool allLoaded = true;
        static constexpr std::array<const char*, 4> kAlfaBackfireSources = {
            "backfire_1", "backfire_2", "backfire_3", "backfire_4"
        };
        for (int index = 0; index < static_cast<int>(kAlfaBackfireSources.size()); ++index) {
            const std::string path = directory + "/" + kAlfaBackfireSources[index] + ".wav";
            const FMOD_RESULT result = core_->createSound(path.c_str(), FMOD_DEFAULT, nullptr, &alfaBackfireSamples_[index]);
            if (result != FMOD_OK || alfaBackfireSamples_[index] == nullptr) {
                allLoaded = false;
                if (alfaBackfireSamples_[index] != nullptr) {
                    alfaBackfireSamples_[index]->release();
                    alfaBackfireSamples_[index] = nullptr;
                }
            }
        }
        alfaBackfireSamplesLoaded_ = allLoaded;
    }

    void loadShiftSamplesLocked(const std::string& directory) {
        const std::array<const char*, 2> names = {"shift_up", "shift_down"};
        for (int index = 0; index < 2; ++index) {
            const std::string path = directory + "/" + names[index] + ".wav";
            if (core_->createSound(path.c_str(), FMOD_DEFAULT, nullptr, &shiftSamples_[index]) != FMOD_OK) {
                for (FMOD::Sound*& sound : shiftSamples_) {
                    if (sound != nullptr) { sound->release(); sound = nullptr; }
                }
                return;
            }
        }
        shiftSamplesLoaded_ = true;
    }

    void playShiftSampleLocked(bool upshift) {
        if (!shiftSamplesLoaded_ || core_ == nullptr) return;
        if (shiftChannel_ != nullptr) {
            shiftChannel_->stop();
            shiftChannel_ = nullptr;
        }
        FMOD::Channel* channel = nullptr;
        const int index = upshift ? 0 : 1;
        if (core_->playSound(shiftSamples_[index], nullptr, true, &channel) != FMOD_OK || channel == nullptr) return;
        channel->setMode(FMOD_2D);
        channel->setVolume(hostEffectsGain_ * gearShiftGain_ * shiftOverrideGain_);
        channel->setPaused(false);
        shiftChannel_ = channel;
    }

    void playAlfaBackfireSampleLocked(int sampleIndex) {
        if (!alfaBackfireSamplesLoaded_ || core_ == nullptr) return;
        int selectedIndex = sampleIndex;
        if (selectedIndex < 1 || selectedIndex > 4 ||
            (backfireAllowedSamplesMask_ & (1 << (selectedIndex - 1))) == 0) {
            selectedIndex = 0;
            for (int candidate = 1; candidate <= 4; ++candidate) {
                if ((backfireAllowedSamplesMask_ & (1 << (candidate - 1))) != 0 &&
                    alfaBackfireSamples_[candidate - 1] != nullptr) {
                    selectedIndex = candidate;
                    break;
                }
            }
            if (selectedIndex == 0) return;
        }
        FMOD::Channel* channel = nullptr;
        const FMOD_RESULT result = core_->playSound(
            alfaBackfireSamples_[selectedIndex - 1],
            nullptr,
            true,
            &channel
        );
        if (result != FMOD_OK || channel == nullptr) return;
        channel->setMode(FMOD_2D);
        // Core one-shots use the same effects host gain and per-category trim as their Studio
        // counterparts, while the sample itself remains an unprocessed Alfa recording.
        channel->setVolume(hostEffectsGain_ * backfireGain_);
        channel->setPaused(false);
        alfaBackfireChannel_ = channel;
    }

    void updateTraceContextLocked(
        float rpm,
        float drivetrainSpeed,
        float throttle,
        float boost,
        float boostAbsolute,
        float bov,
        float bovDecay,
        int gear,
        bool isShifting,
        float shiftProgress,
        std::uint64_t shiftSerial,
        bool,
        int,
        bool limiterPulse,
        bool backfireTriggered,
        bool tractionActive,
        bool tractionPulse,
        std::uint64_t simulationFrameId
    ) {
        if (!diagnosticsEnabled_.load(std::memory_order_relaxed)) {
            return;
        }
        std::lock_guard<std::mutex> diagnosticLock(diagnosticMutex_);
        if (diagnosticRing_ == nullptr) return;
        traceRpm_ = rpm;
        traceDrivetrainSpeed_ = drivetrainSpeed;
        traceThrottle_ = throttle;
        traceBoostNormalized_ = std::max(0.0f, boost);
        traceBoostAbsolute_ = std::max(0.0f, boostAbsolute);
        traceBov_ = std::max(0.0f, bov);
        traceBovDecay_ = std::max(0.0f, bovDecay);
        traceGear_ = gear;
        traceIsShifting_ = isShifting;
        traceShiftProgress_ = std::clamp(shiftProgress, 0.0f, 1.0f);
        traceShiftSerial_ = shiftSerial;
        traceLimiterPulse_ = limiterPulse;
        traceBackfireTriggered_ = backfireTriggered;
        traceTractionActive_ = tractionActive;
        traceTractionPulse_ = tractionPulse;
        traceSimulationFrameId_ = simulationFrameId;
    }

    void setDiagnosticsEnabledLocked(bool enabled) {
        std::lock_guard<std::mutex> diagnosticLock(diagnosticMutex_);
        diagnosticsEnabled_.store(enabled, std::memory_order_relaxed);
        diagnosticRing_ = enabled ? std::make_unique<NativeDiagnosticRing>() : nullptr;
        resetTraceContextLocked();
    }

    void resetTraceContextLocked() {
        traceRpm_ = 0.0f;
        traceDrivetrainSpeed_ = 0.0f;
        traceThrottle_ = 0.0f;
        traceBoostNormalized_ = 0.0f;
        traceBoostAbsolute_ = 0.0f;
        traceBov_ = 0.0f;
        traceBovDecay_ = 0.0f;
        traceGear_ = 0;
        traceIsShifting_ = false;
        traceShiftProgress_ = 0.0f;
        traceShiftSerial_ = 0;
        traceLimiterPulse_ = false;
        traceBackfireTriggered_ = false;
        traceTractionActive_ = false;
        traceTractionPulse_ = false;
        traceSimulationFrameId_ = 0;
    }

    static const char* diagnosticKindText(NativeDiagnosticKind kind) {
        switch (kind) {
            case NativeDiagnosticKind::EventStart: return "EVENT_START";
            case NativeDiagnosticKind::EventStop: return "EVENT_STOP";
            case NativeDiagnosticKind::VoicePlayed: return "VOICE_PLAYED";
            case NativeDiagnosticKind::VoiceStopped: return "VOICE_STOPPED";
            case NativeDiagnosticKind::VoiceState: return "VOICE_STATE";
        }
        return "UNKNOWN";
    }

    static SoundMetadata inspectSoundMetadata(FMOD::Sound* sound) {
        SoundMetadata metadata{};
        if (sound == nullptr) return metadata;

        unsigned int lengthMs = 0;
        if (sound->getLength(&lengthMs, FMOD_TIMEUNIT_MS) == FMOD_OK) {
            metadata.lengthMs = lengthMs;
        }

        FMOD_SOUND_TYPE type = FMOD_SOUND_TYPE_UNKNOWN;
        FMOD_SOUND_FORMAT format = FMOD_SOUND_FORMAT_NONE;
        int channels = 0;
        int bits = 0;
        if (sound->getFormat(&type, &format, &channels, &bits) == FMOD_OK) {
            metadata.channels = std::max(0, channels);
        }

        float frequency = 0.0f;
        int priority = 0;
        if (sound->getDefaults(&frequency, &priority) == FMOD_OK) {
            metadata.rateHz = std::max(0.0f, frequency);
        }
        return metadata;
    }

    void recordDiagnostic(
        NativeDiagnosticKind kind,
        double timestampSeconds,
        const EventSlot* event,
        const std::string& sourceName,
        FMOD_RESULT result,
        std::uint64_t voiceSerial,
        double durationSeconds,
        float audibility,
        float routeGain,
        int voiceCount,
        int virtualVoiceCount,
        int callbackVoiceCount,
        const SoundMetadata& sourceMetadata = {}
    ) {
        if (!diagnosticsEnabled_.load(std::memory_order_relaxed)) return;
        std::lock_guard<std::mutex> diagnosticLock(diagnosticMutex_);
        if (diagnosticRing_ == nullptr) return;
        NativeDiagnosticRecord& record = diagnosticRing_->records[
            diagnosticRing_->writeSequence % kDiagnosticRingCapacity
        ];
        record = {};
        record.sequence = diagnosticRing_->writeSequence++;
        record.timestampSeconds = timestampSeconds;
        record.simulationFrameId = traceSimulationFrameId_;
        record.voiceSerial = voiceSerial;
        record.durationSeconds = durationSeconds;
        record.kind = static_cast<int>(kind);
        record.result = static_cast<int>(result);
        record.gear = traceGear_;
        record.voiceCount = voiceCount;
        record.virtualVoiceCount = virtualVoiceCount;
        record.callbackVoiceCount = callbackVoiceCount;
        record.audibility = audibility;
        record.routeGain = routeGain;
        record.rpm = traceRpm_;
        record.drivetrainSpeed = traceDrivetrainSpeed_;
        record.throttle = traceThrottle_;
        record.boostNormalized = traceBoostNormalized_;
        record.boostAbsolute = traceBoostAbsolute_;
        record.bov = traceBov_;
        record.bovDecay = traceBovDecay_;
        record.shiftProgress = traceShiftProgress_;
        record.shiftSerial = traceShiftSerial_;
        record.stateFlags =
            (traceLimiterPulse_ ? 1 : 0) |
            (traceBackfireTriggered_ ? 2 : 0) |
            (traceTractionActive_ ? 4 : 0) |
            (traceTractionPulse_ ? 8 : 0);
        record.shifting = traceIsShifting_;
        record.sampleLengthMs = sourceMetadata.lengthMs;
        record.sampleChannels = sourceMetadata.channels;
        record.sampleRateHz = sourceMetadata.rateHz;
        if (event != nullptr) {
            copyDiagnosticText(&record.eventName, event->name);
            copyDiagnosticText(&record.eventPath, event->path);
        }
        copyDiagnosticText(&record.sourceName, sourceName);
    }

    void recordEventLifecycle(NativeDiagnosticKind kind, const EventSlot& event, FMOD_RESULT result) {
        recordDiagnostic(kind, monotonicSeconds(), &event, {}, result, 0, -1.0, 0.0f, 0.0f, 0, 0, 0);
    }

    void recordVoiceState(const VoiceAggregate& source, float audibility) {
        EventSlot temporary;
        temporary.name = source.eventName;
        temporary.path = source.eventPath;
        recordDiagnostic(
            NativeDiagnosticKind::VoiceState,
            monotonicSeconds(),
            &temporary,
            source.soundName,
            FMOD_OK,
            0,
            -1.0,
            audibility,
            source.routeGain,
            source.voiceCount,
            source.virtualVoiceCount,
            source.callbackActive ? source.voiceCount : 0,
            SoundMetadata{
                source.sampleLengthMs,
                source.sampleChannels,
                source.sampleRateHz,
            }
        );
    }

    std::vector<std::string> drainDiagnosticRecordsLocked() {
        std::vector<std::string> rows;
        std::lock_guard<std::mutex> diagnosticLock(diagnosticMutex_);
        if (diagnosticRing_ == nullptr) return rows;
        const std::uint64_t oldest = diagnosticRing_->writeSequence > kDiagnosticRingCapacity
            ? diagnosticRing_->writeSequence - kDiagnosticRingCapacity
            : 0;
        diagnosticRing_->readSequence = std::max(diagnosticRing_->readSequence, oldest);
        rows.reserve(static_cast<std::size_t>(diagnosticRing_->writeSequence - diagnosticRing_->readSequence));
        while (diagnosticRing_->readSequence < diagnosticRing_->writeSequence) {
            const NativeDiagnosticRecord& record = diagnosticRing_->records[
                diagnosticRing_->readSequence % kDiagnosticRingCapacity
            ];
            if (record.sequence == diagnosticRing_->readSequence) {
                std::ostringstream row;
                row << diagnosticKindText(static_cast<NativeDiagnosticKind>(record.kind)) << kFieldSeparator
                    << std::fixed << std::setprecision(6) << record.timestampSeconds << kFieldSeparator
                    << record.simulationFrameId << kFieldSeparator
                    << record.voiceSerial << kFieldSeparator
                    << record.durationSeconds << kFieldSeparator
                    << record.result << kFieldSeparator
                    << record.gear << kFieldSeparator
                    << record.voiceCount << kFieldSeparator
                    << record.virtualVoiceCount << kFieldSeparator
                    << record.callbackVoiceCount << kFieldSeparator
                    << record.audibility << kFieldSeparator
                    << record.routeGain << kFieldSeparator
                    << record.rpm << kFieldSeparator
                    << record.drivetrainSpeed << kFieldSeparator
                    << record.throttle << kFieldSeparator
                    << record.boostNormalized << kFieldSeparator
                    << record.boostAbsolute << kFieldSeparator
                    << record.bov << kFieldSeparator
                    << record.bovDecay << kFieldSeparator
                    << record.shiftProgress << kFieldSeparator
                    << record.shiftSerial << kFieldSeparator
                    << record.stateFlags << kFieldSeparator
                    << (record.shifting ? 1 : 0) << kFieldSeparator
                    << record.sampleLengthMs << kFieldSeparator
                    << record.sampleChannels << kFieldSeparator
                    << record.sampleRateHz << kFieldSeparator
                    << record.eventName.data() << kFieldSeparator
                    << record.eventPath.data() << kFieldSeparator
                    << record.sourceName.data();
                rows.push_back(row.str());
            }
            ++diagnosticRing_->readSequence;
        }
        return rows;
    }

    void applyEventOverridesLocked() {
        bool anySolo = false;
        for (const auto& entry : soloEvents_) anySolo = anySolo || entry.second;
        for (auto& pair : slots_) {
            const bool muted = mutedEvents_[pair.first];
            const bool soloed = anySolo && !soloEvents_[pair.first];
            const bool protectedBackfire = backfireOnly_ &&
                (pair.first == "backfire_int" || pair.first == "backfire_ext");
            const bool disabledBackfire = !backfireAudioEnabled_ &&
                (pair.first == "backfire_int" || pair.first == "backfire_ext");
            const bool excludedByBackfireOnly = backfireOnly_ &&
                pair.first != "backfire_int" && pair.first != "backfire_ext";
            const bool isEngine = pair.first == "engine_int" || pair.first == "engine_ext";
            const bool disabledShift = shiftSoundOverride_ &&
                (pair.first == "gear_int" || pair.first == "gear_ext" || pair.first == "gear_grind");
            const bool disabledShiftAudio = !shiftSoundEnabled_ &&
                (pair.first == "gear_int" || pair.first == "gear_ext" || pair.first == "gear_grind");
            const bool disabledTransmission = !transmissionAudioEnabled_ &&
                (pair.first == "transmission" || pair.first == "transmission_ext");
            const bool disabledTurbo = !turboAudioEnabled_ && pair.first == "turbo";
            const float baseGain = isEngine ? hostEngineGain_ : hostEffectsGain_;
            const float categoryGain = eventCategoryGain(pair.first);
            pair.second->instance->setVolume((disabledBackfire || disabledShift || disabledShiftAudio || disabledTransmission || disabledTurbo || (!protectedBackfire && (muted || soloed || excludedByBackfireOnly))) ? 0.0f : baseGain * categoryGain);
        }
    }

    float eventCategoryGain(const std::string& name) const {
        // These are user trims layered after the authored FMOD event mix. They are deliberately
        // keyed by event identity, so changing one family never attenuates engine or other effects.
        if (name == "transmission" || name == "transmission_ext") return transmissionGain_;
        if (name == "gear_int" || name == "gear_ext" || name == "gear_grind") return gearShiftGain_;
        if (name == "turbo") return turboGain_;
        if (name == "backfire_int" || name == "backfire_ext") return backfireGain_;
        return 1.0f;
    }

    static FMOD_RESULT F_CALL eventCallback(
        FMOD_STUDIO_EVENT_CALLBACK_TYPE type,
        FMOD_STUDIO_EVENTINSTANCE* instance,
        void* parameters
    ) {
        auto* eventInstance = reinterpret_cast<FMOD::Studio::EventInstance*>(instance);
        EventSlot* slot = nullptr;
        if (eventInstance == nullptr || eventInstance->getUserData(reinterpret_cast<void**>(&slot)) != FMOD_OK ||
            slot == nullptr || slot->runtime == nullptr) {
            return FMOD_OK;
        }
        if (type == FMOD_STUDIO_EVENT_CALLBACK_SOUND_PLAYED ||
            type == FMOD_STUDIO_EVENT_CALLBACK_SOUND_STOPPED) {
            slot->runtime->onSoundCallback(*slot, type, reinterpret_cast<FMOD::Sound*>(parameters));
        }
        return FMOD_OK;
    }

    std::string failAndCloseLocked(std::string error) {
        lastError_ = std::move(error);
        closeLocked();
        return lastError_;
    }

    void closeLocked() {
        if (alfaBackfireChannel_ != nullptr) {
            alfaBackfireChannel_->stop();
            alfaBackfireChannel_ = nullptr;
        }
        if (shiftChannel_ != nullptr) {
            shiftChannel_->stop();
            shiftChannel_ = nullptr;
        }
        for (FMOD::Sound*& sound : alfaBackfireSamples_) {
            if (sound != nullptr) {
                sound->release();
                sound = nullptr;
            }
        }
        for (FMOD::Sound*& sound : shiftSamples_) {
            if (sound != nullptr) { sound->release(); sound = nullptr; }
        }
        shiftSamplesLoaded_ = false;
        alfaBackfireSamplesLoaded_ = false;
        for (auto& pair : slots_) {
            if (pair.second->instance != nullptr) {
                pair.second->instance->setCallback(nullptr, 0);
                pair.second->instance->setUserData(nullptr);
                pair.second->instance->stop(FMOD_STUDIO_STOP_IMMEDIATE);
                pair.second->instance->release();
                pair.second->instance = nullptr;
            }
        }
        slots_.clear();
        events_.clear();
        eventPaths_.clear();
        eventCatalog_.clear();
        {
            std::lock_guard<std::mutex> callbackLock(callbackMutex_);
            recentSources_.clear();
            voiceStartTimes_.clear();
        }
        if (bank_ != nullptr) {
            bank_->unload();
        }
        if (commonBank_ != nullptr) {
            commonBank_->unload();
        }
        if (commonStringsBank_ != nullptr) {
            commonStringsBank_->unload();
        }
        if (studio_ != nullptr) {
            studio_->unloadAll();
            studio_->release();
        }
        studio_ = nullptr;
        core_ = nullptr;
        bank_ = nullptr;
        commonBank_ = nullptr;
        commonStringsBank_ = nullptr;
        active_ = false;
        hasTurbo_ = false;
        idleRpm_ = 1000.0f;
        limiterRunning_ = false;
        limiterDecay_ = 10.0f;
        tractionDecay_ = 10.0f;
        setDiagnosticsEnabledLocked(false);
        nextVoiceSerial_ = 1;
    }

    void discoverEventsLocked(const std::string& bankPath) {
        eventCatalog_.clear();
        int eventCount = 0;
        if (bank_->getEventCount(&eventCount) == FMOD_OK && eventCount > 0) {
            std::vector<FMOD::Studio::EventDescription*> descriptions(static_cast<std::size_t>(eventCount));
            int actualCount = 0;
            if (bank_->getEventList(descriptions.data(), eventCount, &actualCount) == FMOD_OK) {
                for (int index = 0; index < actualCount; ++index) {
                    char path[512]{};
                    int retrieved = 0;
                    FMOD::Studio::EventDescription* description = descriptions[static_cast<std::size_t>(index)];
                    if (description->getPath(path, sizeof(path), &retrieved) != FMOD_OK) {
                        continue;
                    }
                    const std::string suffix = eventSuffix(path);
                    FMOD_GUID guid{};
                    const std::string guidText = description->getID(&guid) == FMOD_OK
                        ? formatGuid(guid)
                        : std::string{};
                    eventCatalog_.push_back(BankEventCatalogEntry{
                        std::string(path),
                        guidText,
                        suffix,
                        runtimeClassificationForEvent(suffix),
                    });
                    if (isAllowedEventName(suffix)) {
                        events_[suffix] = description;
                        eventPaths_[suffix] = path;
                    }
                }
            }
        }

        const std::size_t separator = bankPath.find_last_of('/');
        if (separator == std::string::npos) {
            return;
        }
        std::ifstream guidFile(bankPath.substr(0, separator + 1) + "GUIDs.txt");
        std::string line;
        while (std::getline(guidFile, line)) {
            const std::size_t space = line.find_first_of(" \t");
            if (space == std::string::npos) {
                continue;
            }
            const std::string path = trimWhitespace(line.substr(space + 1));
            const std::string suffix = eventSuffix(path);
            if (!isAllowedEventName(suffix) || events_.find(suffix) != events_.end()) {
                continue;
            }
            FMOD_GUID guid{};
            if (!parseGuid(line.substr(0, space), &guid)) {
                continue;
            }
            FMOD::Studio::EventDescription* description = nullptr;
            if (studio_->getEventByID(&guid, &description) == FMOD_OK && description != nullptr) {
                events_[suffix] = description;
                eventPaths_[suffix] = path;
            }
        }
    }

    EventSlot* createEventSlotLocked(
        const std::string& name,
        FMOD::Studio::EventDescription* description
    ) {
        auto event = std::make_unique<EventSlot>();
        event->runtime = this;
        event->name = name;
        event->path = eventPaths_[name];
        event->description = description;
        FMOD_RESULT result = description->createInstance(&event->instance);
        if (result != FMOD_OK || event->instance == nullptr) {
            lastError_ = resultText(result, "create " + name);
            return nullptr;
        }
        // Match the requested host mix: engine at unity, ancillary events at +6 dB.
        // This multiplies the authored Studio mix without replacing its automation.
        const bool isEngine = name == "engine_int" || name == "engine_ext";
        event->instance->setVolume((isEngine ? hostEngineGain_ : hostEffectsGain_) * eventCategoryGain(name));
        EventSlot* stable = event.get();
        event->instance->setUserData(stable);
        event->instance->setCallback(
            eventCallback,
            FMOD_STUDIO_EVENT_CALLBACK_SOUND_PLAYED | FMOD_STUDIO_EVENT_CALLBACK_SOUND_STOPPED
        );
        slots_[name] = std::move(event);
        return stable;
    }

    EventSlot* slot(const std::string& name) const {
        const auto found = slots_.find(name);
        return found == slots_.end() ? nullptr : found->second.get();
    }

    FMOD::Studio::EventInstance* slotInstance(const std::string& name) const {
        EventSlot* found = slot(name);
        return found == nullptr ? nullptr : found->instance;
    }

    std::string perspectiveEventLocked(const std::string& cabin, const std::string& exterior) const {
        const bool external = perspective_ == kPerspectiveExterior;
        const std::string& preferred = external ? exterior : cabin;
        const std::string& fallback = external ? cabin : exterior;
        if (slot(preferred) != nullptr) {
            return preferred;
        }
        return slot(fallback) == nullptr ? std::string{} : fallback;
    }

    void initializeParametersLocked() {
        for (const char* name : {"engine_int", "engine_ext"}) {
            setParameterQuietly(slotInstance(name), "rpms", idleRpm_);
        }
        applyAudioThrottlePolicyLocked();
        for (const char* name : {"gear_int", "gear_ext"}) {
            setParameterQuietly(slotInstance(name), "state", 1.0f);
        }
        for (const char* name : {"transmission", "transmission_ext"}) {
            setParameterQuietly(slotInstance(name), "drivetrain_speed", 0.0f);
        }
        for (const char* name : {"tractioncontrol_int", "tractioncontrol_ext"}) {
            setParameterQuietly(slotInstance(name), "decay", tractionDecay_);
        }
        setParameterQuietly(slotInstance("limiter"), "decay", limiterDecay_);
    }

    void applyAudioThrottlePolicyLocked() {
        // These values are deliberately not the physical pedal. They select each authored
        // event's 0 dB endpoint so the app never adds throttle-dependent attenuation. The engine
        // and transmission stay at their load endpoint; backfire uses its lift-off endpoint.
        for (const char* name : {"engine_int", "engine_ext", "transmission", "transmission_ext"}) {
            setParameterQuietly(slotInstance(name), "throttle", kFullLoadAudioThrottle);
        }
        for (const char* name : {"backfire_int", "backfire_ext"}) {
            setParameterQuietly(slotInstance(name), "throttle", kBackfireAudioThrottle);
        }
    }

    void startSelectedContinuousEventsLocked() {
        startEventLocked(perspectiveEventLocked("engine_int", "engine_ext"));
        startEventLocked(perspectiveEventLocked("transmission", "transmission_ext"));
        if (hasTurbo_) {
            startEventLocked("turbo");
        }
    }

    void switchPerspectiveLocked(int perspective) {
        const std::string oldEngine = perspectiveEventLocked("engine_int", "engine_ext");
        const std::string oldTransmission = perspectiveEventLocked("transmission", "transmission_ext");
        perspective_ = perspective;
        setListenerLocked();
        const std::string newEngine = perspectiveEventLocked("engine_int", "engine_ext");
        const std::string newTransmission = perspectiveEventLocked("transmission", "transmission_ext");
        if (oldEngine != newEngine) {
            stopEventLocked(oldEngine, FMOD_STUDIO_STOP_ALLOWFADEOUT);
            startEventLocked(newEngine);
        }
        if (oldTransmission != newTransmission) {
            stopEventLocked(oldTransmission, FMOD_STUDIO_STOP_ALLOWFADEOUT);
            startEventLocked(newTransmission);
        }
        applySpatialAttributesLocked();
    }

    void setListenerLocked() {
        // In the optional exterior-pure mode, placing the listener at the engine emitter
        // neutralizes FMOD distance attenuation and stereo pan without bypassing Studio DSP.
        const FMOD_3D_ATTRIBUTES& listener = perspective_ == kPerspectiveExterior
            ? (exteriorPureAudio_ ? engineAttributes_ : exteriorListenerAttributes_)
            : cabinListenerAttributes_;
        studio_->setListenerAttributes(0, &listener);
    }

    void applySpatialAttributesLocked() {
        for (auto& pair : slots_) {
            const bool backfire = pair.first == "backfire_int" || pair.first == "backfire_ext";
            const FMOD_3D_ATTRIBUTES* attributes = nullptr;
            if (perspective_ == kPerspectiveExterior && exteriorPureAudio_) {
                attributes = &engineAttributes_;
            } else {
                attributes = backfire ? &backfireAttributes_ : &engineAttributes_;
            }
            pair.second->instance->set3DAttributes(attributes);
        }
    }

    void startEventLocked(const std::string& name) {
        FMOD::Studio::EventInstance* instance = slotInstance(name);
        if (instance == nullptr) {
            return;
        }
        instance->setTimelinePosition(0);
        const FMOD_RESULT result = instance->start();
        if (const EventSlot* event = slot(name); event != nullptr) {
            recordEventLifecycle(NativeDiagnosticKind::EventStart, *event, result);
        }
    }

    void stopEventLocked(const std::string& name, FMOD_STUDIO_STOP_MODE mode) {
        FMOD::Studio::EventInstance* instance = slotInstance(name);
        if (instance != nullptr) {
            const FMOD_RESULT result = instance->stop(mode);
            if (const EventSlot* event = slot(name); event != nullptr) {
                recordEventLifecycle(NativeDiagnosticKind::EventStop, *event, result);
            }
        }
    }

    bool eitherBackfirePlayingLocked() const {
        return isPlayingLocked(slotInstance("backfire_int")) ||
            isPlayingLocked(slotInstance("backfire_ext"));
    }

    static bool isPlayingLocked(FMOD::Studio::EventInstance* instance) {
        if (instance == nullptr) {
            return false;
        }
        FMOD_STUDIO_PLAYBACK_STATE state = FMOD_STUDIO_PLAYBACK_STOPPED;
        return instance->getPlaybackState(&state) == FMOD_OK &&
            (state == FMOD_STUDIO_PLAYBACK_PLAYING ||
                state == FMOD_STUDIO_PLAYBACK_STARTING ||
                state == FMOD_STUDIO_PLAYBACK_SUSTAINING);
    }

    static void setParameterQuietly(
        FMOD::Studio::EventInstance* instance,
        const char* name,
        float value
    ) {
        if (instance != nullptr) {
            // Preserve authored parameter seek speed. Forcing an immediate
            // seek is what turns whole-km/h telemetry edges into pitch steps.
            instance->setParameterByName(name, value, false);
        }
    }

    void collectVoiceSnapshotsLocked(std::unordered_map<std::string, VoiceAggregate>* aggregates) {
        for (auto& pair : slots_) {
            FMOD::ChannelGroup* root = nullptr;
            if (pair.second->instance->getChannelGroup(&root) != FMOD_OK || root == nullptr) {
                continue;
            }
            visitChannelGroupLocked(*pair.second, root, root, aggregates);
        }
    }

    void visitChannelGroupLocked(
        const EventSlot& event,
        FMOD::ChannelGroup* group,
        FMOD::ChannelGroup* eventRoot,
        std::unordered_map<std::string, VoiceAggregate>* aggregates
    ) {
        int channelCount = 0;
        if (group->getNumChannels(&channelCount) == FMOD_OK) {
            for (int index = 0; index < channelCount; ++index) {
                FMOD::Channel* channel = nullptr;
                if (group->getChannel(index, &channel) != FMOD_OK || channel == nullptr) {
                    continue;
                }
                FMOD::Sound* sound = nullptr;
                if (channel->getCurrentSound(&sound) != FMOD_OK || sound == nullptr) {
                    continue;
                }
                char soundName[512]{};
                if (sound->getName(soundName, sizeof(soundName)) != FMOD_OK || soundName[0] == '\0') {
                    std::strncpy(soundName, "<unnamed sound>", sizeof(soundName) - 1);
                }
                const std::string id = stableSourceId(event.path, soundName);
                float audibility = 0.0f;
                channel->getAudibility(&audibility);
                bool isVirtual = false;
                channel->isVirtual(&isVirtual);
                VoiceAggregate& aggregate = (*aggregates)[id];
                aggregate.id = id;
                aggregate.eventPath = event.path;
                aggregate.eventName = event.name;
                aggregate.soundName = soundName;
                if (diagnosticsEnabled_.load(std::memory_order_relaxed)) {
                    // A capture can begin while a looping source is already alive, before it
                    // receives a SOUND_PLAYED callback. Snapshot-only inspection fills that
                    // gap outside FMOD's callback path and gives the offline join its immutable
                    // duration/channel/rate identity without touching release playback.
                    const SoundMetadata metadata = inspectSoundMetadata(sound);
                    if (metadata.lengthMs > 0) aggregate.sampleLengthMs = metadata.lengthMs;
                    if (metadata.channels > 0) aggregate.sampleChannels = metadata.channels;
                    if (metadata.rateHz > 0.0f) aggregate.sampleRateHz = metadata.rateHz;
                }
                aggregate.audibilitySquared += audibility * audibility;
                aggregate.routeGain = std::max(
                    aggregate.routeGain,
                    routeGainLocked(channel, eventRoot)
                );
                ++aggregate.voiceCount;
                if (isVirtual) {
                    ++aggregate.virtualVoiceCount;
                }
            }
        }

        int childCount = 0;
        if (group->getNumGroups(&childCount) != FMOD_OK) {
            return;
        }
        for (int index = 0; index < childCount; ++index) {
            FMOD::ChannelGroup* child = nullptr;
            if (group->getGroup(index, &child) == FMOD_OK && child != nullptr) {
                visitChannelGroupLocked(event, child, eventRoot, aggregates);
            }
        }
    }

    static float routeGainLocked(FMOD::Channel* channel, FMOD::ChannelGroup* eventRoot) {
        float gain = 1.0f;
        float channelVolume = 1.0f;
        if (channel->getVolume(&channelVolume) == FMOD_OK) {
            gain *= channelVolume;
        }
        FMOD::ChannelGroup* group = nullptr;
        if (channel->getChannelGroup(&group) != FMOD_OK) {
            return gain;
        }
        while (group != nullptr) {
            float groupVolume = 1.0f;
            if (group->getVolume(&groupVolume) == FMOD_OK) {
                gain *= groupVolume;
            }
            if (group == eventRoot) {
                break;
            }
            FMOD::ChannelGroup* parent = nullptr;
            if (group->getParentGroup(&parent) != FMOD_OK || parent == group) {
                break;
            }
            group = parent;
        }
        return std::max(0.0f, gain);
    }

    void mergeRecentSourcesLocked(std::unordered_map<std::string, VoiceAggregate>* aggregates) {
        const double now = monotonicSeconds();
        std::lock_guard<std::mutex> callbackLock(callbackMutex_);
        for (auto iterator = recentSources_.begin(); iterator != recentSources_.end();) {
            const bool recent = now - iterator->second.lastSeenSeconds <= kRecentSourceSeconds;
            if (!recent) {
                iterator = recentSources_.erase(iterator);
                continue;
            }
            VoiceAggregate& aggregate = (*aggregates)[iterator->first];
            aggregate.id = iterator->first;
            aggregate.eventPath = iterator->second.eventPath;
            aggregate.eventName = iterator->second.eventName;
            aggregate.soundName = iterator->second.soundName;
            aggregate.sampleLengthMs = iterator->second.sampleLengthMs;
            aggregate.sampleChannels = iterator->second.sampleChannels;
            aggregate.sampleRateHz = iterator->second.sampleRateHz;
            aggregate.callbackActive = iterator->second.callbackVoiceCount > 0;
            ++iterator;
        }
    }

    std::mutex mutex_;
    std::mutex callbackMutex_;
    FMOD::Studio::System* studio_ = nullptr;
    FMOD::System* core_ = nullptr;
    FMOD::Studio::Bank* bank_ = nullptr;
    FMOD::Studio::Bank* commonStringsBank_ = nullptr;
    FMOD::Studio::Bank* commonBank_ = nullptr;
    FMOD_DSP_DESCRIPTION distanceFilter_{};
    FMOD_DSP_DESCRIPTION gain_{};
    std::unordered_map<std::string, FMOD::Studio::EventDescription*> events_;
    std::unordered_map<std::string, std::string> eventPaths_;
    std::vector<BankEventCatalogEntry> eventCatalog_;
    std::unordered_map<std::string, std::unique_ptr<EventSlot>> slots_;
    std::unordered_map<std::string, bool> mutedEvents_;
    std::unordered_map<std::string, bool> soloEvents_;
    float hostEngineGain_ = 1.0f;
    float hostEffectsGain_ = 2.0f;
    float transmissionGain_ = 1.0f;
    float gearShiftGain_ = 1.0f;
    float turboGain_ = 1.0f;
    float backfireGain_ = 1.0f;
    bool backfireOnly_ = false;
    bool backfireAudioEnabled_ = true;
    bool backfireUseOriginal_ = false;
    bool shiftSoundOverride_ = false;
    float shiftOverrideGain_ = 0.5f;
    bool shiftSoundEnabled_ = true;
    bool transmissionAudioEnabled_ = true;
    bool turboAudioEnabled_ = true;
    int backfireAllowedSamplesMask_ = 0x0F;
    std::array<FMOD::Sound*, 4> alfaBackfireSamples_{};
    FMOD::Channel* alfaBackfireChannel_ = nullptr;
    bool alfaBackfireSamplesLoaded_ = false;
    std::array<FMOD::Sound*, 2> shiftSamples_{};
    FMOD::Channel* shiftChannel_ = nullptr;
    bool shiftSamplesLoaded_ = false;
    std::unordered_map<std::string, RecentSource> recentSources_;
    // Debug-only start times make STOPPED records report the observed one-shot duration. The map
    // is untouched when diagnostics are disabled, keeping release callbacks on the existing path.
    std::unordered_map<std::uint64_t, double> voiceStartTimes_;
    FMOD_3D_ATTRIBUTES engineAttributes_{};
    FMOD_3D_ATTRIBUTES backfireAttributes_{};
    FMOD_3D_ATTRIBUTES cabinListenerAttributes_{};
    FMOD_3D_ATTRIBUTES exteriorListenerAttributes_{};
    std::string lastError_;
    float limiterDecay_ = 10.0f;
    float tractionDecay_ = 10.0f;
    int perspective_ = 0;
    bool active_ = false;
    bool exteriorPureAudio_ = false;
    bool hasTurbo_ = false;
    float idleRpm_ = 1000.0f;
    bool limiterRunning_ = false;
    std::atomic<bool> diagnosticsEnabled_{false};
    std::mutex diagnosticMutex_;
    std::unique_ptr<NativeDiagnosticRing> diagnosticRing_;
    std::uint64_t nextVoiceSerial_ = 1;
    float traceRpm_ = 0.0f;
    float traceDrivetrainSpeed_ = 0.0f;
    float traceThrottle_ = 0.0f;
    float traceBoostNormalized_ = 0.0f;
    float traceBoostAbsolute_ = 0.0f;
    float traceBov_ = 0.0f;
    float traceBovDecay_ = 0.0f;
    int traceGear_ = 0;
    bool traceIsShifting_ = false;
    float traceShiftProgress_ = 0.0f;
    std::uint64_t traceShiftSerial_ = 0;
    bool traceLimiterPulse_ = false;
    bool traceBackfireTriggered_ = false;
    bool traceTractionActive_ = false;
    bool traceTractionPulse_ = false;
    std::uint64_t traceSimulationFrameId_ = 0;
};

FmodRuntime runtime;

std::string utfString(JNIEnv* environment, jstring value) {
    if (value == nullptr) {
        return {};
    }
    const char* chars = environment->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) {
        return {};
    }
    std::string copied(chars);
    environment->ReleaseStringUTFChars(value, chars);
    return copied;
}

jobjectArray toJavaStringArray(JNIEnv* environment, const std::vector<std::string>& values) {
    jclass stringClass = environment->FindClass("java/lang/String");
    jobjectArray result = environment->NewObjectArray(
        static_cast<jsize>(values.size()),
        stringClass,
        nullptr
    );
    for (std::size_t index = 0; index < values.size(); ++index) {
        jstring value = environment->NewStringUTF(values[index].c_str());
        environment->SetObjectArrayElement(result, static_cast<jsize>(index), value);
        environment->DeleteLocalRef(value);
    }
    return result;
}

std::vector<std::string> stringArray(JNIEnv* environment, jobjectArray values) {
    std::vector<std::string> result;
    if (values == nullptr) return result;
    const jsize count = environment->GetArrayLength(values);
    result.reserve(static_cast<std::size_t>(count));
    for (jsize index = 0; index < count; ++index) {
        auto* value = static_cast<jstring>(environment->GetObjectArrayElement(values, index));
        result.push_back(utfString(environment, value));
        environment->DeleteLocalRef(value);
    }
    return result;
}

jstring resultString(JNIEnv* environment, const std::string& result) {
    if (result.empty()) {
        return nullptr;
    }
    __android_log_print(ANDROID_LOG_ERROR, kLogTag, "%s", result.c_str());
    return environment->NewStringUTF(result.c_str());
}

std::array<float, 12> spatialArray(JNIEnv* environment, jfloatArray values) {
    std::array<float, 12> result{};
    if (values == nullptr || environment->GetArrayLength(values) < static_cast<jsize>(result.size())) {
        return result;
    }
    environment->GetFloatArrayRegion(values, 0, static_cast<jsize>(result.size()), result.data());
    return result;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_gabrielpc_enginesoundsimulator_audio_NativeFmodBankBridge_open(
    JNIEnv* environment,
    jobject,
    jstring commonStringsBankPath,
    jstring commonBankPath,
    jstring carBankPath,
    jstring alfaBackfireDirectory,
    jint perspective,
    jboolean hasTurbo,
    jfloat idleRpm,
    jfloatArray spatial,
    jboolean diagnosticsEnabled
) {
    return resultString(
        environment,
        runtime.open(
            utfString(environment, commonStringsBankPath),
            utfString(environment, commonBankPath),
            utfString(environment, carBankPath),
            utfString(environment, alfaBackfireDirectory),
            perspective,
            hasTurbo == JNI_TRUE,
            idleRpm,
            spatialArray(environment, spatial),
            diagnosticsEnabled == JNI_TRUE
        )
    );
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_gabrielpc_enginesoundsimulator_audio_NativeFmodBankBridge_update(
    JNIEnv* environment,
    jobject,
    jfloat dt,
    jfloat rpm,
    jfloat drivetrainSpeed,
    jfloat throttle,
    jint perspective,
    jfloat boost,
    jfloat boostAbsolute,
    jfloat bov,
    jfloat bovDecay,
    jint gear,
    jboolean isShifting,
    jfloat shiftProgress,
    jlong shiftSerial,
    jint limiterPulseCount,
    jint shiftStartedCount,
    jint shiftDirection,
    jint shiftRejectedCount,
    jint backfirePulseCount,
    jint backfireSampleIndex,
    jboolean tractionActive,
    jint tractionPulseCount,
    jlong simulationFrameId
) {
    return resultString(
        environment,
        runtime.update(
            dt,
            rpm,
            drivetrainSpeed,
            throttle,
            perspective,
            boost,
            boostAbsolute,
            bov,
            bovDecay,
            gear,
            isShifting == JNI_TRUE,
            shiftProgress,
            static_cast<std::uint64_t>(std::max<jlong>(0, shiftSerial)),
            limiterPulseCount,
            shiftStartedCount,
            shiftDirection,
            shiftRejectedCount,
            backfirePulseCount,
            backfireSampleIndex,
            tractionActive == JNI_TRUE,
            tractionPulseCount,
            static_cast<std::uint64_t>(std::max<jlong>(0, simulationFrameId))
        )
    );
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_gabrielpc_enginesoundsimulator_audio_NativeFmodBankBridge_voiceSnapshots(
    JNIEnv* environment,
    jobject
) {
    return toJavaStringArray(environment, runtime.voiceSnapshots());
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_gabrielpc_enginesoundsimulator_audio_NativeFmodBankBridge_diagnosticRecords(
    JNIEnv* environment,
    jobject
) {
    return toJavaStringArray(environment, runtime.diagnosticRecords());
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_gabrielpc_enginesoundsimulator_audio_NativeFmodBankBridge_eventCatalog(
    JNIEnv* environment,
    jobject
) {
    return toJavaStringArray(environment, runtime.eventCatalog());
}

extern "C" JNIEXPORT void JNICALL
Java_com_gabrielpc_enginesoundsimulator_audio_NativeFmodBankBridge_setDiagnosticsEnabled(
    JNIEnv*,
    jobject,
    jboolean enabled
) {
    runtime.setDiagnosticsEnabled(enabled == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_gabrielpc_enginesoundsimulator_audio_NativeFmodBankBridge_setEventOverrides(
    JNIEnv* environment, jobject, jobjectArray mutedEvents, jobjectArray soloEvents
) {
    runtime.setEventOverrides(stringArray(environment, mutedEvents), stringArray(environment, soloEvents));
}

extern "C" JNIEXPORT void JNICALL
Java_com_gabrielpc_enginesoundsimulator_audio_NativeFmodBankBridge_setHostGains(
    JNIEnv*, jobject, jfloat engine, jfloat effects
) {
    runtime.setHostGains(engine, effects);
}

extern "C" JNIEXPORT void JNICALL
Java_com_gabrielpc_enginesoundsimulator_audio_NativeFmodBankBridge_setCategoryGains(
    JNIEnv*, jobject, jfloat transmission, jfloat gearShift, jfloat turbo, jfloat backfire
) {
    runtime.setCategoryGains(transmission, gearShift, turbo, backfire);
}

extern "C" JNIEXPORT void JNICALL
Java_com_gabrielpc_enginesoundsimulator_audio_NativeFmodBankBridge_setBackfireOnly(
    JNIEnv*, jobject, jboolean enabled
) {
    runtime.setBackfireOnly(enabled == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_gabrielpc_enginesoundsimulator_audio_NativeFmodBankBridge_setBackfireAudioEnabled(
    JNIEnv*, jobject, jboolean enabled
) {
    runtime.setBackfireAudioEnabled(enabled == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_gabrielpc_enginesoundsimulator_audio_NativeFmodBankBridge_setBackfireAllowedSamples(
    JNIEnv*, jobject, jint mask
) {
    runtime.setBackfireAllowedSamples(mask);
}

extern "C" JNIEXPORT void JNICALL
Java_com_gabrielpc_enginesoundsimulator_audio_NativeFmodBankBridge_setShiftSoundOverride(
    JNIEnv*, jobject, jboolean enabled
) {
    runtime.setShiftSoundOverride(enabled == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_gabrielpc_enginesoundsimulator_audio_NativeFmodBankBridge_setShiftSoundEnabled(
    JNIEnv*, jobject, jboolean enabled
) {
    runtime.setShiftSoundEnabled(enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_gabrielpc_enginesoundsimulator_audio_NativeFmodBankBridge_setShiftOverrideGain(
        JNIEnv*, jobject, jfloat gain) {
    runtime.setShiftOverrideGain(gain);
}

extern "C" JNIEXPORT void JNICALL
Java_com_gabrielpc_enginesoundsimulator_audio_NativeFmodBankBridge_setTransmissionAudioEnabled(
    JNIEnv*, jobject, jboolean enabled
) {
    runtime.setTransmissionAudioEnabled(enabled == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_gabrielpc_enginesoundsimulator_audio_NativeFmodBankBridge_setTurboAudioEnabled(
    JNIEnv*, jobject, jboolean enabled
) {
    runtime.setTurboAudioEnabled(enabled == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_gabrielpc_enginesoundsimulator_audio_NativeFmodBankBridge_setBackfireUseOriginal(
    JNIEnv*, jobject, jboolean enabled
) {
    runtime.setBackfireUseOriginal(enabled == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_gabrielpc_enginesoundsimulator_audio_NativeFmodBankBridge_setExteriorPureAudio(
    JNIEnv*, jobject, jboolean enabled
) {
    runtime.setExteriorPureAudio(enabled == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_gabrielpc_enginesoundsimulator_audio_NativeFmodBankBridge_close(JNIEnv*, jobject) {
    runtime.close();
}
