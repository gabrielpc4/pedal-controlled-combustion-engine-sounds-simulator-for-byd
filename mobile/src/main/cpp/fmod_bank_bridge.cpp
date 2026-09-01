#include <jni.h>

#include <android/log.h>
#include <fmod.hpp>
#include <fmod_dsp.h>
#include <fmod_studio.hpp>

#include <algorithm>
#include <array>
#include <cctype>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <mutex>
#include <sstream>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

namespace {

constexpr char kLogTag[] = "FmodBankRuntime";
constexpr int kFmodOutputRate = 48000;
constexpr unsigned int kFmodDspBlockSize = 64;
constexpr int kFmodDspBlocks = 4;
constexpr int kFmodLogicalChannelCap = 512;
constexpr int kPerspectiveExterior = 1;
constexpr int kSourceLoad = 0;
constexpr int kSourceCoast = 1;
constexpr int kSourceBoth = 2;
constexpr float kDisabledEventGain = 0.0001f;
constexpr float kMeterFloorDb = -80.0f;

enum MeterTrackIndex {
    kMeterEngineLoad,
    kMeterEngineCoast,
    kMeterTransmission,
    kMeterTurbo,
    kMeterLimiter,
    kMeterGear,
    kMeterOverrun,
    kMeterTrackCount,
};

constexpr std::array<const char*, 10> kAllowedEventNames = {
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
};

constexpr char kStartupEvent[] = "start";

bool isAllowedEventName(const std::string& name) {
    return std::find_if(
        kAllowedEventNames.begin(),
        kAllowedEventNames.end(),
        [&name](const char* candidate) { return name == candidate; }
    ) != kAllowedEventNames.end() || name == kStartupEvent;
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

FMOD_3D_ATTRIBUTES centeredAttributes() {
    FMOD_3D_ATTRIBUTES attributes{};
    attributes.position = {0.0f, 0.5f, 0.0f};
    attributes.velocity = {0.0f, 0.0f, 0.0f};
    attributes.forward = {0.0f, 0.0f, 1.0f};
    attributes.up = {0.0f, 1.0f, 0.0f};
    return attributes;
}

FMOD_RESULT F_CALLBACK passthroughRead(
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

FMOD_RESULT F_CALLBACK acceptDistanceFilterAttributes(FMOD_DSP_STATE*, int, void*, unsigned int) {
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

FMOD_RESULT F_CALLBACK createGain(FMOD_DSP_STATE* state) {
    if (state == nullptr) {
        return FMOD_ERR_INVALID_PARAM;
    }
    state->plugindata = new GainState();
    return FMOD_OK;
}

FMOD_RESULT F_CALLBACK releaseGain(FMOD_DSP_STATE* state) {
    if (state != nullptr) {
        delete static_cast<GainState*>(state->plugindata);
        state->plugindata = nullptr;
    }
    return FMOD_OK;
}

FMOD_RESULT F_CALLBACK setGainDecibels(FMOD_DSP_STATE* state, int, float value) {
    if (state != nullptr && state->plugindata != nullptr) {
        static_cast<GainState*>(state->plugindata)->decibels = value;
    }
    return FMOD_OK;
}

FMOD_RESULT F_CALLBACK setGainInverted(FMOD_DSP_STATE* state, int, FMOD_BOOL value) {
    if (state != nullptr && state->plugindata != nullptr) {
        static_cast<GainState*>(state->plugindata)->inverted = value != 0;
    }
    return FMOD_OK;
}

FMOD_RESULT F_CALLBACK applyGain(
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

class FmodRuntime {
public:
    std::string open(
        const std::string& commonStringsBankPath,
        const std::string& commonBankPath,
        const std::string& carBankPath,
        int perspective,
        int source
    ) {
        std::lock_guard<std::mutex> lock(mutex_);
        closeLocked();

        FMOD_RESULT result = FMOD::Studio::System::create(&studio_);
        if (result != FMOD_OK) {
            return resultText(result, "Studio::System::create");
        }

        result = studio_->getLowLevelSystem(&core_);
        if (result != FMOD_OK) {
            return failAndCloseLocked(resultText(result, "getLowLevelSystem"));
        }
        result = core_->setSoftwareFormat(kFmodOutputRate, FMOD_SPEAKERMODE_STEREO, 0);
        if (result != FMOD_OK) {
            return failAndCloseLocked(resultText(result, "setSoftwareFormat"));
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
        if (events_.find("engine_int") == events_.end()) {
            return failAndCloseLocked("The installed bank has no engine_int event.");
        }

        const std::string engineName = selectPerspectiveEventLocked("engine_int", "engine_ext", perspective);
        if (engineName.empty()) {
            return failAndCloseLocked("The installed bank has no playable engine event.");
        }

        if (source == kSourceBoth) {
            engineLoad_ = createPersistentLocked(engineName, 1.0f);
            engineCoast_ = createPersistentLocked(engineName, 0.0f);
        } else {
            engineLoad_ = createPersistentLocked(engineName, source == kSourceLoad ? 1.0f : 0.0f);
            engineCoast_ = nullptr;
        }
        if (engineLoad_ == nullptr || (source == kSourceBoth && engineCoast_ == nullptr)) {
            return failAndCloseLocked(lastError_);
        }

        const std::string transmissionName = selectPerspectiveEventLocked("transmission", "transmission_ext", perspective);
        if (!transmissionName.empty()) {
            transmission_ = createPersistentLocked(transmissionName, 1.0f);
        }
        if (events_.find("turbo") != events_.end()) {
            turbo_ = createPersistentLocked("turbo", 1.0f);
        }
        if (events_.find("limiter") != events_.end()) {
            limiter_ = createPersistentLocked("limiter", 1.0f);
        }
        const std::string gearName = selectPerspectiveEventLocked("gear_int", "gear_ext", perspective);
        if (!gearName.empty()) {
            gear_ = createDormantLocked(gearName);
        }
        const std::string backfireName = selectPerspectiveEventLocked("backfire_int", "backfire_ext", perspective);
        if (!backfireName.empty()) {
            backfire_ = createDormantLocked(backfireName);
        }
        if (events_.find(kStartupEvent) != events_.end()) {
            playDetachedOneShotLocked(kStartupEvent, 1.0f, 1.0f, 0.0f, 0.0f);
        }

        result = studio_->update();
        if (result != FMOD_OK) {
            return failAndCloseLocked(resultText(result, "initial Studio::System::update"));
        }

        source_ = source;
        perspective_ = perspective;
        active_ = true;
        lastShiftSerial_ = 0;
        activeNames_.clear();
        activeNames_.push_back(engineName);
        if (engineCoast_ != nullptr) {
            activeNames_.push_back("engine_coast");
        }
        if (transmission_ != nullptr) activeNames_.push_back(transmissionName);
        if (turbo_ != nullptr) activeNames_.push_back("turbo");
        if (limiter_ != nullptr) activeNames_.push_back("limiter");
        if (gear_ != nullptr) activeNames_.push_back(gearName);
        if (backfire_ != nullptr) activeNames_.push_back(backfireName);
        return {};
    }

    std::string update(
        float rpm,
        float throttle,
        float masterGain,
        float loadGain,
        float coastGain,
        float transmissionGain,
        float turboGain,
        float limiterGain,
        float shiftGain,
        float overrunGain,
        float boost,
        float bovDecay,
        jlong shiftSerial,
        jint shiftDirection,
        bool triggerOverrun
    ) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!active_ || studio_ == nullptr) {
            return "FMOD bank runtime is not active.";
        }

        const float cleanRpm = std::max(0.0f, rpm);
        const float cleanThrottle = std::clamp(throttle, 0.0f, 1.0f);
        const float cleanMaster = std::clamp(masterGain, 0.0f, 1.2f);
        applyEngineParametersLocked(engineLoad_, cleanRpm, source_ == kSourceCoast ? 0.0f : 1.0f);
        setVolumeQuietly(engineLoad_, cleanMaster * (source_ == kSourceCoast ? coastGain : loadGain));
        if (engineCoast_ != nullptr) {
            applyEngineParametersLocked(engineCoast_, cleanRpm, 0.0f);
            setVolumeQuietly(engineCoast_, cleanMaster * coastGain);
        }

        if (transmission_ != nullptr) {
            setParameterQuietly(transmission_, "drivetrain_speed", cleanRpm * 0.10471976f);
            setParameterQuietly(transmission_, "throttle", 1.0f);
            setVolumeQuietly(transmission_, cleanMaster * transmissionGain);
        }
        if (turbo_ != nullptr) {
            setParameterQuietly(turbo_, "rpms", cleanRpm);
            setParameterQuietly(turbo_, "boost", std::clamp(boost, 0.0f, 1.0f));
            setParameterQuietly(turbo_, "bov", std::clamp(bovDecay, 0.0f, 1.0f));
            setParameterQuietly(turbo_, "bov_decay", std::clamp(bovDecay, 0.0f, 1.0f));
            setVolumeQuietly(turbo_, cleanMaster * turboGain);
        }
        if (limiter_ != nullptr) {
            setParameterQuietly(limiter_, "rpms", cleanRpm);
            setParameterQuietly(limiter_, "decay", cleanThrottle > 0.05f ? 0.0f : 10.0f);
            setVolumeQuietly(limiter_, cleanMaster * limiterGain);
        }

        setVolumeQuietly(gear_, cleanMaster * shiftGain);
        setVolumeQuietly(backfire_, cleanMaster * overrunGain);

        if (shiftSerial != lastShiftSerial_) {
            lastShiftSerial_ = shiftSerial;
            if (shiftDirection != 0 && cleanMaster * shiftGain > kDisabledEventGain && !isPlayingLocked(gear_)) {
                // Assetto Corsa owns one gear event per perspective. Do not stack a
                // new instance over an authored shift that is still fading out.
                stopQuietly(gear_, FMOD_STUDIO_STOP_ALLOWFADEOUT);
                setParameterQuietly(gear_, "state", shiftDirection > 0 ? 1.0f : 0.0f);
                setParameterQuietly(gear_, "rpms", cleanRpm);
                setParameterQuietly(gear_, "throttle", cleanThrottle);
                startQuietly(gear_);
            }
        }
        if (triggerOverrun && cleanMaster * overrunGain > kDisabledEventGain && !isPlayingLocked(backfire_)) {
            setParameterQuietly(backfire_, "rpms", cleanRpm);
            setParameterQuietly(backfire_, "throttle", 0.0f);
            startQuietly(backfire_);
        }

        const FMOD_RESULT result = studio_->update();
        if (result != FMOD_OK) {
            return resultText(result, "Studio::System::update");
        }
        return {};
    }

    std::array<float, kMeterTrackCount> outputMeters() {
        std::lock_guard<std::mutex> lock(mutex_);
        std::array<float, kMeterTrackCount> meters{};
        meters.fill(kMeterFloorDb);
        if (!active_) {
            return meters;
        }

        if (source_ == kSourceCoast) {
            meters[kMeterEngineCoast] = meterDbLocked(engineLoad_);
        } else {
            meters[kMeterEngineLoad] = meterDbLocked(engineLoad_);
        }
        if (engineCoast_ != nullptr) {
            meters[kMeterEngineCoast] = meterDbLocked(engineCoast_);
        }
        meters[kMeterTransmission] = meterDbLocked(transmission_);
        meters[kMeterTurbo] = meterDbLocked(turbo_);
        meters[kMeterLimiter] = meterDbLocked(limiter_);
        meters[kMeterGear] = meterDbLocked(gear_);
        meters[kMeterOverrun] = meterDbLocked(backfire_);
        return meters;
    }

    void close() {
        std::lock_guard<std::mutex> lock(mutex_);
        closeLocked();
    }

    std::vector<std::string> activeEventNames() {
        std::lock_guard<std::mutex> lock(mutex_);
        return activeNames_;
    }

private:
    std::string failAndCloseLocked(std::string error) {
        lastError_ = std::move(error);
        closeLocked();
        return lastError_;
    }

    void closeLocked() {
        stopAndReleaseLocked(engineLoad_);
        stopAndReleaseLocked(engineCoast_);
        stopAndReleaseLocked(transmission_);
        stopAndReleaseLocked(turbo_);
        stopAndReleaseLocked(limiter_);
        stopAndReleaseLocked(gear_);
        stopAndReleaseLocked(backfire_);
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
        engineLoad_ = nullptr;
        engineCoast_ = nullptr;
        transmission_ = nullptr;
        turbo_ = nullptr;
        limiter_ = nullptr;
        gear_ = nullptr;
        backfire_ = nullptr;
        events_.clear();
        activeNames_.clear();
        active_ = false;
        lastShiftSerial_ = 0;
    }

    static void stopAndReleaseLocked(FMOD::Studio::EventInstance*& instance) {
        if (instance == nullptr) {
            return;
        }
        instance->stop(FMOD_STUDIO_STOP_IMMEDIATE);
        instance->release();
        instance = nullptr;
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
                    if (descriptions[static_cast<std::size_t>(index)]->getPath(path, sizeof(path), &retrieved) != FMOD_OK) {
                        continue;
                    }
                    const std::string suffix = eventSuffix(path);
                    if (isAllowedEventName(suffix)) {
                        events_[suffix] = descriptions[static_cast<std::size_t>(index)];
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
            const std::string suffix = eventSuffix(line.substr(space + 1));
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
            }
        }
    }

    std::string selectPerspectiveEventLocked(
        const std::string& interior,
        const std::string& exterior,
        int perspective
    ) const {
        const bool wantsExterior = perspective == kPerspectiveExterior;
        const std::string& first = wantsExterior ? exterior : interior;
        const std::string& fallback = wantsExterior ? interior : exterior;
        if (events_.find(first) != events_.end()) {
            return first;
        }
        if (events_.find(fallback) != events_.end()) {
            return fallback;
        }
        return {};
    }

    FMOD::Studio::EventInstance* createPersistentLocked(const std::string& name, float throttle) {
        const auto iterator = events_.find(name);
        if (iterator == events_.end()) {
            return nullptr;
        }
        FMOD::Studio::EventInstance* instance = nullptr;
        FMOD_RESULT result = iterator->second->createInstance(&instance);
        if (result != FMOD_OK || instance == nullptr) {
            lastError_ = resultText(result, "create " + name);
            return nullptr;
        }
        const FMOD_3D_ATTRIBUTES attributes = centeredAttributes();
        instance->set3DAttributes(&attributes);
        setParameterQuietly(instance, "throttle", throttle);
        result = instance->start();
        if (result != FMOD_OK) {
            instance->release();
            lastError_ = resultText(result, "start " + name);
            return nullptr;
        }
        enableMeteringQuietly(instance);
        return instance;
    }

    FMOD::Studio::EventInstance* createDormantLocked(const std::string& name) {
        const auto iterator = events_.find(name);
        if (iterator == events_.end()) {
            return nullptr;
        }
        FMOD::Studio::EventInstance* instance = nullptr;
        if (iterator->second->createInstance(&instance) != FMOD_OK || instance == nullptr) {
            return nullptr;
        }
        const FMOD_3D_ATTRIBUTES attributes = centeredAttributes();
        instance->set3DAttributes(&attributes);
        enableMeteringQuietly(instance);
        return instance;
    }

    void playDetachedOneShotLocked(
        const std::string& name,
        float volume,
        float state,
        float rpm,
        float throttle
    ) {
        const auto iterator = events_.find(name);
        if (iterator == events_.end()) {
            return;
        }
        FMOD::Studio::EventInstance* instance = nullptr;
        if (iterator->second->createInstance(&instance) != FMOD_OK || instance == nullptr) {
            return;
        }
        const FMOD_3D_ATTRIBUTES attributes = centeredAttributes();
        instance->set3DAttributes(&attributes);
        setParameterQuietly(instance, "state", state);
        setParameterQuietly(instance, "rpms", rpm);
        setParameterQuietly(instance, "throttle", throttle);
        setVolumeQuietly(instance, volume);
        instance->start();
        instance->release();
    }

    static void startQuietly(FMOD::Studio::EventInstance* instance) {
        if (instance != nullptr) {
            instance->start();
            enableMeteringQuietly(instance);
        }
    }

    static void stopQuietly(FMOD::Studio::EventInstance* instance, FMOD_STUDIO_STOP_MODE mode) {
        if (instance != nullptr) {
            instance->stop(mode);
        }
    }

    static bool isPlayingLocked(FMOD::Studio::EventInstance* instance) {
        if (instance == nullptr) {
            return false;
        }
        FMOD_STUDIO_PLAYBACK_STATE state = FMOD_STUDIO_PLAYBACK_STOPPED;
        return instance->getPlaybackState(&state) == FMOD_OK &&
            (state == FMOD_STUDIO_PLAYBACK_PLAYING || state == FMOD_STUDIO_PLAYBACK_STARTING || state == FMOD_STUDIO_PLAYBACK_SUSTAINING);
    }

    static void enableMeteringQuietly(FMOD::Studio::EventInstance* instance) {
        if (instance == nullptr) {
            return;
        }
        FMOD::ChannelGroup* group = nullptr;
        if (instance->getChannelGroup(&group) == FMOD_OK && group != nullptr) {
            int dspCount = 0;
            if (group->getNumDSPs(&dspCount) == FMOD_OK && dspCount > 0) {
                FMOD::DSP* dsp = nullptr;
                if (group->getDSP(dspCount - 1, &dsp) == FMOD_OK && dsp != nullptr) {
                    dsp->setMeteringEnabled(false, true);
                }
            }
        }
    }

    static float meterDbLocked(FMOD::Studio::EventInstance* instance) {
        if (instance == nullptr) {
            return kMeterFloorDb;
        }
        FMOD::ChannelGroup* group = nullptr;
        if (instance->getChannelGroup(&group) != FMOD_OK || group == nullptr) {
            return kMeterFloorDb;
        }
        int dspCount = 0;
        if (group->getNumDSPs(&dspCount) != FMOD_OK || dspCount <= 0) {
            return kMeterFloorDb;
        }
        FMOD::DSP* dsp = nullptr;
        if (group->getDSP(dspCount - 1, &dsp) != FMOD_OK || dsp == nullptr) {
            return kMeterFloorDb;
        }
        dsp->setMeteringEnabled(false, true);
        FMOD_DSP_METERING_INFO output{};
        if (dsp->getMeteringInfo(nullptr, &output) != FMOD_OK || output.numchannels <= 0) {
            return kMeterFloorDb;
        }
        float sumSquares = 0.0f;
        for (int channel = 0; channel < output.numchannels; ++channel) {
            const float rms = std::max(0.0f, output.rmslevel[channel]);
            sumSquares += rms * rms;
        }
        const float rms = std::sqrt(sumSquares / static_cast<float>(output.numchannels));
        return rms <= 0.0000001f ? kMeterFloorDb : std::max(kMeterFloorDb, 20.0f * std::log10(rms));
    }

    static void setParameterQuietly(FMOD::Studio::EventInstance* instance, const char* name, float value) {
        if (instance != nullptr) {
            instance->setParameterValue(name, value);
        }
    }

    static void setVolumeQuietly(FMOD::Studio::EventInstance* instance, float value) {
        if (instance != nullptr) {
            instance->setVolume(std::clamp(value, 0.0f, 4.0f));
        }
    }

    static void applyEngineParametersLocked(FMOD::Studio::EventInstance* instance, float rpm, float throttle) {
        setParameterQuietly(instance, "rpms", rpm);
        setParameterQuietly(instance, "throttle", throttle);
    }

    std::mutex mutex_;
    FMOD::Studio::System* studio_ = nullptr;
    FMOD::System* core_ = nullptr;
    FMOD::Studio::Bank* bank_ = nullptr;
    FMOD::Studio::Bank* commonStringsBank_ = nullptr;
    FMOD::Studio::Bank* commonBank_ = nullptr;
    FMOD::Studio::EventInstance* engineLoad_ = nullptr;
    FMOD::Studio::EventInstance* engineCoast_ = nullptr;
    FMOD::Studio::EventInstance* transmission_ = nullptr;
    FMOD::Studio::EventInstance* turbo_ = nullptr;
    FMOD::Studio::EventInstance* limiter_ = nullptr;
    FMOD::Studio::EventInstance* gear_ = nullptr;
    FMOD::Studio::EventInstance* backfire_ = nullptr;
    FMOD_DSP_DESCRIPTION distanceFilter_{};
    FMOD_DSP_DESCRIPTION gain_{};
    std::unordered_map<std::string, FMOD::Studio::EventDescription*> events_;
    std::vector<std::string> activeNames_;
    std::string lastError_;
    jlong lastShiftSerial_ = 0;
    int source_ = kSourceLoad;
    int perspective_ = 0;
    bool active_ = false;
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

jstring resultString(JNIEnv* environment, const std::string& result) {
    if (result.empty()) {
        return nullptr;
    }
    __android_log_print(ANDROID_LOG_ERROR, kLogTag, "%s", result.c_str());
    return environment->NewStringUTF(result.c_str());
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
    jint source
) {
    return resultString(
        environment,
        runtime.open(
            utfString(environment, commonStringsBankPath),
            utfString(environment, commonBankPath),
            utfString(environment, carBankPath),
            perspective,
            source
        )
    );
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_gabrielpc_enginesoundsimulator_audio_NativeFmodBankBridge_update(
    JNIEnv* environment,
    jobject,
    jfloat rpm,
    jfloat throttle,
    jfloat masterGain,
    jfloat loadGain,
    jfloat coastGain,
    jfloat transmissionGain,
    jfloat turboGain,
    jfloat limiterGain,
    jfloat shiftGain,
    jfloat overrunGain,
    jfloat boost,
    jfloat bovDecay,
    jlong shiftSerial,
    jint shiftDirection,
    jboolean triggerOverrun
) {
    return resultString(
        environment,
        runtime.update(
            rpm,
            throttle,
            masterGain,
            loadGain,
            coastGain,
            transmissionGain,
            turboGain,
            limiterGain,
            shiftGain,
            overrunGain,
            boost,
            bovDecay,
            shiftSerial,
            shiftDirection,
            triggerOverrun == JNI_TRUE
        )
    );
}

extern "C" JNIEXPORT void JNICALL
Java_com_gabrielpc_enginesoundsimulator_audio_NativeFmodBankBridge_close(JNIEnv*, jobject) {
    runtime.close();
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_gabrielpc_enginesoundsimulator_audio_NativeFmodBankBridge_outputMeters(JNIEnv* environment, jobject) {
    const std::array<float, kMeterTrackCount> meters = runtime.outputMeters();
    jfloatArray result = environment->NewFloatArray(kMeterTrackCount);
    if (result != nullptr) {
        environment->SetFloatArrayRegion(result, 0, kMeterTrackCount, meters.data());
    }
    return result;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_gabrielpc_enginesoundsimulator_audio_NativeFmodBankBridge_activeEventNames(JNIEnv* environment, jobject) {
    const std::vector<std::string> names = runtime.activeEventNames();
    jclass stringClass = environment->FindClass("java/lang/String");
    jobjectArray result = environment->NewObjectArray(static_cast<jsize>(names.size()), stringClass, nullptr);
    for (std::size_t index = 0; index < names.size(); ++index) {
        environment->SetObjectArrayElement(
            result,
            static_cast<jsize>(index),
            environment->NewStringUTF(names[index].c_str())
        );
    }
    return result;
}
