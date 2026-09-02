#include <jni.h>

#include <android/log.h>
#include <fmod.hpp>
#include <fmod_studio.hpp>

#include <algorithm>
#include <array>
#include <chrono>
#include <cctype>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <fstream>
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
constexpr double kRecentSourceSeconds = 1.5;
constexpr char kFieldSeparator = '\x1f';
constexpr char kStableIdSeparator = '\x1e';

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
    double lastSeenSeconds = 0.0;
    int callbackVoiceCount = 0;
};

struct VoiceAggregate {
    std::string id;
    std::string eventPath;
    std::string eventName;
    std::string soundName;
    float audibilitySquared = 0.0f;
    float routeGain = 0.0f;
    int voiceCount = 0;
    int virtualVoiceCount = 0;
    bool callbackActive = false;
};

class FmodRuntime {
public:
    std::string open(
        const std::string& commonStringsBankPath,
        const std::string& commonBankPath,
        const std::string& carBankPath,
        int perspective,
        bool hasTurbo,
        float idleRpm,
        const std::array<float, 12>& spatial
    ) {
        std::lock_guard<std::mutex> lock(mutex_);
        closeLocked();

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
        for (auto& pair : slots_) {
            const bool backfire = pair.first == "backfire_int" || pair.first == "backfire_ext";
            pair.second->instance->set3DAttributes(backfire ? &backfireAttributes_ : &engineAttributes_);
        }

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
        float bov,
        float bovDecay,
        bool limiterPulse,
        bool shiftStarted,
        int shiftDirection,
        bool shiftRejected,
        bool backfireTriggered,
        bool tractionActive,
        bool tractionPulse
    ) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!active_ || studio_ == nullptr) {
            return "FMOD runtime is not active.";
        }

        const float cleanDt = std::clamp(dt, 0.0001f, 0.1f);
        const float cleanRpm = std::max(1.0f, rpm);
        const float cleanThrottle = std::clamp(throttle, 0.0f, 1.0f);
        if (perspective != perspective_) {
            switchPerspectiveLocked(perspective);
        }

        for (const char* name : {"engine_int", "engine_ext"}) {
            setParameterQuietly(slotInstance(name), "rpms", cleanRpm);
            setParameterQuietly(slotInstance(name), "throttle", cleanThrottle);
        }
        for (const char* name : {"backfire_int", "backfire_ext"}) {
            setParameterQuietly(slotInstance(name), "throttle", cleanThrottle);
        }
        for (const char* name : {"transmission", "transmission_ext"}) {
            setParameterQuietly(slotInstance(name), "drivetrain_speed", drivetrainSpeed);
            setParameterQuietly(slotInstance(name), "throttle", cleanThrottle);
        }

        EventSlot* turbo = slot("turbo");
        if (turbo != nullptr && hasTurbo_) {
            setParameterQuietly(turbo->instance, "boost", std::max(0.0f, boost));
            setParameterQuietly(turbo->instance, "bov", std::max(0.0f, bov));
            setParameterQuietly(turbo->instance, "bov_decay", std::max(0.0f, bovDecay));
        }

        limiterDecay_ += cleanDt;
        if (limiterPulse) {
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

        if (shiftStarted && shiftDirection != 0) {
            const std::string selected = perspective_ == kPerspectiveExterior
                ? "gear_ext"
                : "gear_int";
            if (slot(selected) != nullptr && !isPlayingLocked(slotInstance(selected))) {
                stopEventLocked(selected, FMOD_STUDIO_STOP_ALLOWFADEOUT);
                setParameterQuietly(slotInstance(selected), "state", shiftDirection > 0 ? 1.0f : 0.0f);
                startEventLocked(selected);
            }
        }
        if (shiftRejected && !isPlayingLocked(slotInstance("gear_grind"))) {
            startEventLocked("gear_grind");
        }

        if (backfireTriggered && !eitherBackfirePlayingLocked()) {
            const std::string selected = perspectiveEventLocked("backfire_int", "backfire_ext");
            startEventLocked(selected);
        }

        if (tractionActive || tractionPulse) {
            tractionDecay_ = 0.0f;
        } else {
            tractionDecay_ = std::min(10.0f, tractionDecay_ + cleanDt);
        }
        for (const char* name : {"tractioncontrol_int", "tractioncontrol_ext"}) {
            setParameterQuietly(slotInstance(name), "decay", tractionDecay_);
        }
        if (tractionActive || tractionPulse) {
            const std::string selected = perspectiveEventLocked(
                "tractioncontrol_int",
                "tractioncontrol_ext"
            );
            if (!selected.empty() && !isPlayingLocked(slotInstance(selected))) {
                startEventLocked(selected);
            }
        }

        const FMOD_RESULT result = studio_->update();
        if (result != FMOD_OK) {
            return resultText(result, "Studio::System::update");
        }
        return {};
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
        recent.lastSeenSeconds = monotonicSeconds();
        if (type == FMOD_STUDIO_EVENT_CALLBACK_SOUND_PLAYED) {
            ++recent.callbackVoiceCount;
        } else if (type == FMOD_STUDIO_EVENT_CALLBACK_SOUND_STOPPED) {
            recent.callbackVoiceCount = std::max(0, recent.callbackVoiceCount - 1);
        }
    }

private:
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
        {
            std::lock_guard<std::mutex> callbackLock(callbackMutex_);
            recentSources_.clear();
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
    }

    void discoverEventsLocked(const std::string& bankPath) {
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
            setParameterQuietly(slotInstance(name), "throttle", 0.0f);
        }
        for (const char* name : {"backfire_int", "backfire_ext"}) {
            setParameterQuietly(slotInstance(name), "throttle", 0.0f);
        }
        for (const char* name : {"gear_int", "gear_ext"}) {
            setParameterQuietly(slotInstance(name), "state", 1.0f);
        }
        for (const char* name : {"transmission", "transmission_ext"}) {
            setParameterQuietly(slotInstance(name), "drivetrain_speed", 0.0f);
            setParameterQuietly(slotInstance(name), "throttle", 0.0f);
        }
        for (const char* name : {"tractioncontrol_int", "tractioncontrol_ext"}) {
            setParameterQuietly(slotInstance(name), "decay", tractionDecay_);
        }
        setParameterQuietly(slotInstance("limiter"), "decay", limiterDecay_);
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
        const std::string oldTraction = perspectiveEventLocked("tractioncontrol_int", "tractioncontrol_ext");
        const bool tractionWasPlaying = isPlayingLocked(slotInstance(oldTraction));
        perspective_ = perspective;
        setListenerLocked();
        const std::string newEngine = perspectiveEventLocked("engine_int", "engine_ext");
        const std::string newTransmission = perspectiveEventLocked("transmission", "transmission_ext");
        const std::string newTraction = perspectiveEventLocked("tractioncontrol_int", "tractioncontrol_ext");
        if (oldEngine != newEngine) {
            stopEventLocked(oldEngine, FMOD_STUDIO_STOP_ALLOWFADEOUT);
            startEventLocked(newEngine);
        }
        if (oldTransmission != newTransmission) {
            stopEventLocked(oldTransmission, FMOD_STUDIO_STOP_ALLOWFADEOUT);
            startEventLocked(newTransmission);
        }
        if (oldTraction != newTraction && tractionWasPlaying) {
            stopEventLocked(oldTraction, FMOD_STUDIO_STOP_ALLOWFADEOUT);
            startEventLocked(newTraction);
        }
    }

    void setListenerLocked() {
        const FMOD_3D_ATTRIBUTES& listener = perspective_ == kPerspectiveExterior
            ? exteriorListenerAttributes_
            : cabinListenerAttributes_;
        studio_->setListenerAttributes(0, &listener);
    }

    void startEventLocked(const std::string& name) {
        FMOD::Studio::EventInstance* instance = slotInstance(name);
        if (instance == nullptr) {
            return;
        }
        instance->setTimelinePosition(0);
        instance->start();
    }

    void stopEventLocked(const std::string& name, FMOD_STUDIO_STOP_MODE mode) {
        FMOD::Studio::EventInstance* instance = slotInstance(name);
        if (instance != nullptr) {
            instance->stop(mode);
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
    std::unordered_map<std::string, std::unique_ptr<EventSlot>> slots_;
    std::unordered_map<std::string, RecentSource> recentSources_;
    FMOD_3D_ATTRIBUTES engineAttributes_{};
    FMOD_3D_ATTRIBUTES backfireAttributes_{};
    FMOD_3D_ATTRIBUTES cabinListenerAttributes_{};
    FMOD_3D_ATTRIBUTES exteriorListenerAttributes_{};
    std::string lastError_;
    float limiterDecay_ = 10.0f;
    float tractionDecay_ = 10.0f;
    int perspective_ = 0;
    bool active_ = false;
    bool hasTurbo_ = false;
    float idleRpm_ = 1000.0f;
    bool limiterRunning_ = false;
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
    jint perspective,
    jboolean hasTurbo,
    jfloat idleRpm,
    jfloatArray spatial
) {
    return resultString(
        environment,
        runtime.open(
            utfString(environment, commonStringsBankPath),
            utfString(environment, commonBankPath),
            utfString(environment, carBankPath),
            perspective,
            hasTurbo == JNI_TRUE,
            idleRpm,
            spatialArray(environment, spatial)
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
    jfloat bov,
    jfloat bovDecay,
    jboolean limiterPulse,
    jboolean shiftStarted,
    jint shiftDirection,
    jboolean shiftRejected,
    jboolean backfireTriggered,
    jboolean tractionActive,
    jboolean tractionPulse
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
            bov,
            bovDecay,
            limiterPulse == JNI_TRUE,
            shiftStarted == JNI_TRUE,
            shiftDirection,
            shiftRejected == JNI_TRUE,
            backfireTriggered == JNI_TRUE,
            tractionActive == JNI_TRUE,
            tractionPulse == JNI_TRUE
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

extern "C" JNIEXPORT void JNICALL
Java_com_gabrielpc_enginesoundsimulator_audio_NativeFmodBankBridge_close(JNIEnv*, jobject) {
    runtime.close();
}
