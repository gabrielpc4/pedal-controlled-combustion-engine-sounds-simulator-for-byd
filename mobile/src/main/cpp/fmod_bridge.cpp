#include <jni.h>

#include <android/log.h>
#include <dlfcn.h>
#include <fmod.h>
#include <fmod_errors.h>
#include <fmod_studio.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <initializer_list>
#include <iomanip>
#include <limits>
#include <memory>
#include <mutex>
#include <new>
#include <sstream>
#include <string>
#include <string_view>
#include <unordered_map>

namespace {

constexpr const char *kLogTag = "BYD-FMOD";
constexpr std::size_t kControlBufferBytes = 112;
constexpr std::int32_t kControlSchemaVersion = 2;
constexpr std::size_t kSchemaOffset = 0;
constexpr std::size_t kEnabledMaskOffset = 4;
constexpr std::size_t kRpmOffset = 8;
constexpr std::size_t kEngineThrottleOffset = 12;
constexpr std::size_t kBoostOffset = 16;
constexpr std::size_t kBovOffset = 20;
constexpr std::size_t kBovDecayOffset = 24;
constexpr std::size_t kLimiterDecayOffset = 28;
constexpr std::size_t kMasterGainOffset = 32;
constexpr std::size_t kEngineGainOffset = 36;
constexpr std::size_t kTurboGainOffset = 40;
constexpr std::size_t kLimiterGainOffset = 44;
constexpr std::size_t kShiftGainOffset = 48;
constexpr std::size_t kBackfireGainOffset = 52;
constexpr std::size_t kShiftDirectionOffset = 56;
constexpr std::size_t kShiftSerialOffset = 64;
constexpr std::size_t kLimiterSerialOffset = 72;
constexpr std::size_t kBovSerialOffset = 80;
constexpr std::size_t kBackfireSerialOffset = 88;
constexpr std::size_t kDrivetrainSpeedOffset = 96;
constexpr std::size_t kTransmissionThrottleOffset = 100;
constexpr std::size_t kTransmissionGainOffset = 104;

constexpr std::int32_t kAudioEnabled = 1 << 0;
constexpr std::int32_t kEngineEnabled = 1 << 1;
constexpr std::int32_t kTurboEnabled = 1 << 2;
constexpr std::int32_t kLimiterEnabled = 1 << 3;
constexpr std::int32_t kShiftEnabled = 1 << 4;
constexpr std::int32_t kBackfireEnabled = 1 << 5;
constexpr std::int32_t kTransmissionEnabled = 1 << 6;
constexpr std::int32_t kKnownEnabledMask =
    kAudioEnabled | kEngineEnabled | kTurboEnabled | kLimiterEnabled |
    kShiftEnabled | kBackfireEnabled | kTransmissionEnabled;
constexpr const char *kStringsBankPath = "file:///android_asset/fmod/common.strings.bank";
constexpr const char *kCommonBankPath = "file:///android_asset/fmod/common.bank";
constexpr std::size_t kOneShotPoolSize = 8;
constexpr float kSilenceThreshold = 1.0e-6f;
constexpr unsigned int kDeviceDspBufferFrames = 64;
constexpr int kDeviceDspBufferCount = 4;
constexpr unsigned int kValidationDspBufferFrames = 512;
constexpr int kValidationDspBufferCount = 4;
constexpr float kLimiterPulseRearmSeconds = 0.01f;
constexpr unsigned int kControlUpdatesPerLimiterPulse = 400 / 50;
constexpr unsigned int kLimiterPulseZeroHoldUpdates = 3;

void logError(const std::string &detail) {
    __android_log_write(ANDROID_LOG_ERROR, kLogTag, detail.c_str());
}

enum class EventKind : std::size_t {
    Engine,
    Turbo,
    Limiter,
    Shifts,
    Backfire,
    Transmission,
    Count,
};

enum class EngineTransition {
    LimiterPulse,
    ShiftDrop,
    LiftBackfire,
};

constexpr std::uint32_t kEmbeddedLimiter = 1u << 0;
constexpr std::uint32_t kEmbeddedShift = 1u << 1;
constexpr std::uint32_t kEmbeddedBackfire = 1u << 2;

const char *kindName(EventKind kind) {
    switch (kind) {
        case EventKind::Engine: return "ENGINE";
        case EventKind::Turbo: return "TURBO";
        case EventKind::Limiter: return "LIMITER";
        case EventKind::Shifts: return "SHIFTS";
        case EventKind::Backfire: return "BACKFIRE";
        case EventKind::Transmission: return "TRANSMISSION";
        case EventKind::Count: break;
    }
    return "UNKNOWN";
}

struct EventSpec {
    EventKind kind;
    const char *path;
    FMOD_GUID guid;
    std::array<const char *, 3> parameters;
    std::size_t parameterCount;
    float triggerValue = 0.01f;
};

struct ProfileSpec {
    const char *id;
    const char *legacyAlias;
    const char *bankAssetPath;
    const char *bankFileName;
    FMOD_GUID bankGuid;
    bool hasEventPathMetadata;
    float idleRpm;
    float highRpm;
    float maximumDrivetrainSpeed;
    float validationBoost;
    std::uint32_t embeddedValidation;
    const EventSpec *events;
    std::size_t eventCount;
};

bool containsAsciiIgnoreCase(std::string_view haystack, std::string_view needle) {
    if (needle.empty()) return true;
    if (needle.size() > haystack.size()) return false;
    const auto asciiLower = [](unsigned char value) {
        return value >= 'A' && value <= 'Z'
            ? static_cast<unsigned char>(value + ('a' - 'A'))
            : value;
    };
    for (std::size_t offset = 0; offset + needle.size() <= haystack.size(); ++offset) {
        bool matches = true;
        for (std::size_t index = 0; index < needle.size(); ++index) {
            if (asciiLower(static_cast<unsigned char>(haystack[offset + index])) !=
                asciiLower(static_cast<unsigned char>(needle[index]))) {
                matches = false;
                break;
            }
        }
        if (matches) return true;
    }
    return false;
}

constexpr FMOD_GUID guid(
    unsigned int a, unsigned short b, unsigned short c,
    unsigned char d0, unsigned char d1, unsigned char d2, unsigned char d3,
    unsigned char d4, unsigned char d5, unsigned char d6, unsigned char d7) {
    return {a, b, c, {d0, d1, d2, d3, d4, d5, d6, d7}};
}

constexpr EventSpec kSkylineEvents[] = {
    {EventKind::Engine, "event:/cars/ks_nissan_skyline_r34/engine_int",
     guid(0x4dc2bcfa, 0x509f, 0x4cec, 0x90, 0xb5, 0xf1, 0x8f, 0x39, 0x94, 0x0f, 0x65),
     {"rpms", "throttle", nullptr}, 2},
    {EventKind::Turbo, "event:/cars/ks_nissan_skyline_r34/turbo",
     guid(0x591bbaac, 0x7e8b, 0x4e46, 0x99, 0xa0, 0xd0, 0xf9, 0xec, 0x9e, 0x65, 0x68),
     {"boost", "bov", "bov_decay"}, 3},
    {EventKind::Limiter, "event:/cars/ks_nissan_skyline_r34/limiter",
     guid(0xbd8ea933, 0x9e48, 0x4c25, 0xaf, 0x24, 0x74, 0x9d, 0x37, 0x78, 0xe2, 0x85),
     {"decay", nullptr, nullptr}, 1},
    {EventKind::Shifts, "event:/cars/ks_nissan_skyline_r34/gear_int",
     guid(0x54e83e8c, 0x2365, 0x4978, 0xb4, 0x0f, 0x5c, 0x79, 0xac, 0x2a, 0x3e, 0x5e),
     {"state", nullptr, nullptr}, 1},
    {EventKind::Backfire, "event:/cars/ks_nissan_skyline_r34/backfire_int",
     guid(0xb8e2dd29, 0x06b4, 0x4f10, 0xa5, 0xa5, 0x7e, 0x90, 0xa5, 0xbd, 0xb5, 0x71),
     {"throttle", nullptr, nullptr}, 1},
};

constexpr EventSpec kHuracanEvents[] = {
    {EventKind::Engine, "event:/cars/fx_lamborghini_huracan_trofeo_evo2/engine_int",
     guid(0x752bc95e, 0x9da1, 0x49bc, 0x82, 0x14, 0x80, 0x68, 0x1a, 0x78, 0xda, 0x6c),
     {"rpms", "throttle", nullptr}, 2},
    {EventKind::Shifts, "event:/cars/fx_lamborghini_huracan_trofeo_evo2/gear_int",
     guid(0x5205eb2b, 0x0fca, 0x45f0, 0xb4, 0x9f, 0xd7, 0x86, 0x8d, 0x84, 0xbb, 0x3c),
     {"state", nullptr, nullptr}, 1},
    {EventKind::Backfire, "event:/cars/fx_lamborghini_huracan_trofeo_evo2/backfire_ext",
     guid(0xc643a4fe, 0x64a0, 0x4954, 0x88, 0x5d, 0x6d, 0x34, 0x1f, 0x66, 0xfc, 0xad),
     {"throttle", nullptr, nullptr}, 1},
    {EventKind::Transmission, "event:/cars/fx_lamborghini_huracan_trofeo_evo2/transmission",
     guid(0xfb26c601, 0xb8e7, 0x4df1, 0xbf, 0xfe, 0x8b, 0x01, 0xda, 0xc5, 0x7a, 0x81),
     {"drivetrain_speed", "throttle", nullptr}, 2},
};

constexpr EventSpec kAventadorEvents[] = {
    {EventKind::Engine, "event:/cars/tr_lamborghini_aventador_sv/engine_int",
     guid(0x6cb6a0ee, 0x9c84, 0x410a, 0xba, 0x44, 0x27, 0x02, 0x3c, 0x86, 0x1a, 0x77),
     {"rpms", "throttle", nullptr}, 2},
    {EventKind::Transmission, "event:/cars/tr_lamborghini_aventador_sv/transmission",
     guid(0xcc2f6139, 0x2e3e, 0x4390, 0x89, 0x5a, 0xdc, 0x5f, 0x00, 0xab, 0xb2, 0xca),
     {"drivetrain_speed", "throttle", nullptr}, 2},
};

constexpr EventSpec kAlfaEvents[] = {
    {EventKind::Engine, "event:/cars/ks_alfa_romeo_4c/engine_int",
     guid(0x22821cdc, 0x9832, 0x44ad, 0x98, 0xe9, 0xca, 0x32, 0x12, 0x08, 0x53, 0x53),
     {"rpms", "throttle", nullptr}, 2},
    {EventKind::Turbo, "event:/cars/ks_alfa_romeo_4c/turbo",
     guid(0x2abdb44e, 0x4229, 0x4472, 0x9f, 0x57, 0xaf, 0x3f, 0x3e, 0xf9, 0x33, 0xc4),
     {"boost", "bov", "bov_decay"}, 3},
    {EventKind::Limiter, "event:/cars/ks_alfa_romeo_4c/limiter",
     guid(0xbdad6001, 0x12d2, 0x4c58, 0x87, 0x37, 0x86, 0x27, 0x0a, 0x64, 0x6a, 0xe2),
     {"decay", nullptr, nullptr}, 1},
    {EventKind::Shifts, "event:/cars/ks_alfa_romeo_4c/gear_int",
     guid(0x5a671ccf, 0x6e08, 0x4f25, 0xaf, 0x46, 0xa2, 0x70, 0xa2, 0x95, 0x4f, 0x33),
     {"state", nullptr, nullptr}, 1},
    {EventKind::Backfire, "event:/cars/ks_alfa_romeo_4c/backfire_int",
     guid(0x278d445d, 0x798a, 0x41dd, 0xae, 0x68, 0xdb, 0xa7, 0xf1, 0xd8, 0x4a, 0x57),
     {"throttle", nullptr, nullptr}, 1, 0.0f},
};

constexpr EventSpec kSupraEvents[] = {
    {EventKind::Engine, "event:/cars/zesty_toyota_supra_mk4_shuto_street/engine_int",
     guid(0x0f36224e, 0x063b, 0x448b, 0xb8, 0xfb, 0x9e, 0xed, 0xe1, 0xef, 0x20, 0x95),
     {"rpms", "throttle", nullptr}, 2},
    {EventKind::Turbo, "event:/cars/zesty_toyota_supra_mk4_shuto_street/turbo",
     guid(0xb349f7ee, 0xe56d, 0x410f, 0xab, 0x3c, 0x86, 0x7b, 0x68, 0x80, 0x7d, 0x71),
     {"boost", "bov", "bov_decay"}, 3},
    {EventKind::Shifts, "event:/cars/zesty_toyota_supra_mk4_shuto_street/gear_int",
     guid(0x79053c8e, 0xb974, 0x409f, 0x9d, 0x38, 0xba, 0xcc, 0xe8, 0xba, 0xd7, 0x0d),
     {"state", nullptr, nullptr}, 1},
    {EventKind::Backfire, "event:/cars/zesty_toyota_supra_mk4_shuto_street/backfire_int",
     guid(0x40c1fa3a, 0x71df, 0x4081, 0xb0, 0x66, 0x54, 0xc2, 0x84, 0x5e, 0xe0, 0xde),
     {"throttle", nullptr, nullptr}, 1},
};

constexpr ProfileSpec kProfiles[] = {
    {"nissan_skyline_r34_cabin", nullptr,
     "file:///android_asset/fmod/ks_nissan_skyline_r34.bank", "ks_nissan_skyline_r34.bank",
     guid(0xce941cbe, 0xfe23, 0x4184, 0xac, 0xd1, 0x67, 0xf4, 0x3f, 0x60, 0x9c, 0xbf),
     true, 800.0f, 8000.0f, 0.0f, 0.333f, 0,
     kSkylineEvents, std::size(kSkylineEvents)},
    {"lamborghini_huracan_trofeo_evo2_cabin", "fx_lamborghini_huracan_trofeo_evo2",
     "file:///android_asset/fmod/fx_lamborghini_huracan_trofeo_evo2.bank",
     "fx_lamborghini_huracan_trofeo_evo2.bank",
     guid(0x40e767d1, 0x1f6e, 0x4f72, 0xb0, 0x10, 0x39, 0x25, 0xa7, 0x25, 0x69, 0xc6),
     false, 1040.0f, 8350.0f, 260.0f, 0.0f, kEmbeddedLimiter,
     kHuracanEvents, std::size(kHuracanEvents)},
    {"lamborghini_aventador_sv_cabin", "tr_lamborghini_aventador_sv",
     "file:///android_asset/fmod/tr_lamborghini_aventador_sv.bank", "tr_lamborghini_aventador_sv.bank",
     guid(0x513e17ef, 0xcf00, 0x4135, 0x82, 0x7f, 0x4b, 0x29, 0xa3, 0x0a, 0xf3, 0x27),
     false, 850.0f, 8500.0f, 350.0f, 0.0f,
     kEmbeddedLimiter | kEmbeddedShift | kEmbeddedBackfire,
     kAventadorEvents, std::size(kAventadorEvents)},
    {"ks_alfa_romeo_4c", "alfa_romeo_4c_cabin",
     "file:///android_asset/fmod/ks_alfa_romeo_4c.bank", "ks_alfa_romeo_4c.bank",
     guid(0x026643c1, 0x7a2f, 0x486c, 0x98, 0x3e, 0x52, 0xb2, 0x41, 0xbf, 0x4a, 0x19),
     true, 850.0f, 6750.0f, 0.0f, 0.95625f, 0,
     kAlfaEvents, std::size(kAlfaEvents)},
    {"zesty_toyota_supra_mk4_shuto_street", "toyota_supra_mk4_cabin",
     "file:///android_asset/fmod/zesty_toyota_supra_mk4_shuto_street.bank",
     "zesty_toyota_supra_mk4_shuto_street.bank",
     guid(0x072e5002, 0x4521, 0x4f3e, 0x88, 0xfe, 0x72, 0x45, 0xf3, 0xb3, 0x04, 0xd4),
     false, 980.0f, 8000.0f, 0.0f, 1.0f, kEmbeddedLimiter,
     kSupraEvents, std::size(kSupraEvents)},
};

const ProfileSpec *findProfile(std::string_view id) {
    for (const auto &profile : kProfiles) {
        if (id == profile.id || (profile.legacyAlias != nullptr && id == profile.legacyAlias)) {
            return &profile;
        }
    }
    return nullptr;
}

struct FmodApi {
    void *coreLibrary = nullptr;
    void *studioLibrary = nullptr;
    decltype(&::FMOD_System_SetOutput) System_SetOutput = nullptr;
    decltype(&::FMOD_System_SetSoftwareFormat) System_SetSoftwareFormat = nullptr;
    decltype(&::FMOD_System_SetDSPBufferSize) System_SetDSPBufferSize = nullptr;
    decltype(&::FMOD_System_GetDSPBufferSize) System_GetDSPBufferSize = nullptr;
    decltype(&::FMOD_System_MixerSuspend) System_MixerSuspend = nullptr;
    decltype(&::FMOD_System_MixerResume) System_MixerResume = nullptr;
    decltype(&::FMOD_System_CreateDSP) System_CreateDSP = nullptr;
    decltype(&::FMOD_System_GetMasterChannelGroup) System_GetMasterChannelGroup = nullptr;
    decltype(&::FMOD_ChannelGroup_AddDSP) ChannelGroup_AddDSP = nullptr;
    decltype(&::FMOD_ChannelGroup_RemoveDSP) ChannelGroup_RemoveDSP = nullptr;
    decltype(&::FMOD_DSP_Release) DSP_Release = nullptr;
    decltype(&::FMOD_Sound_GetName) Sound_GetName = nullptr;
    decltype(&::FMOD_Studio_System_Create) Studio_System_Create = nullptr;
    decltype(&::FMOD_Studio_System_Initialize) Studio_System_Initialize = nullptr;
    decltype(&::FMOD_Studio_System_Release) Studio_System_Release = nullptr;
    decltype(&::FMOD_Studio_System_Update) Studio_System_Update = nullptr;
    decltype(&::FMOD_Studio_System_GetLowLevelSystem) Studio_System_GetLowLevelSystem = nullptr;
    decltype(&::FMOD_Studio_System_GetEventByID) Studio_System_GetEventByID = nullptr;
    decltype(&::FMOD_Studio_System_SetListenerAttributes) Studio_System_SetListenerAttributes = nullptr;
    decltype(&::FMOD_Studio_System_LoadBankFile) Studio_System_LoadBankFile = nullptr;
    decltype(&::FMOD_Studio_Bank_GetID) Bank_GetID = nullptr;
    decltype(&::FMOD_Studio_System_RegisterPlugin) Studio_System_RegisterPlugin = nullptr;
    decltype(&::FMOD_Studio_System_UnregisterPlugin) Studio_System_UnregisterPlugin = nullptr;
    decltype(&::FMOD_Studio_System_UnloadAll) Studio_System_UnloadAll = nullptr;
    decltype(&::FMOD_Studio_System_FlushSampleLoading) Studio_System_FlushSampleLoading = nullptr;
    decltype(&::FMOD_Studio_EventDescription_GetPath) EventDescription_GetPath = nullptr;
    decltype(&::FMOD_Studio_EventDescription_GetParameter) EventDescription_GetParameter = nullptr;
    decltype(&::FMOD_Studio_EventDescription_CreateInstance) EventDescription_CreateInstance = nullptr;
    decltype(&::FMOD_Studio_EventDescription_LoadSampleData) EventDescription_LoadSampleData = nullptr;
    decltype(&::FMOD_Studio_EventInstance_SetVolume) EventInstance_SetVolume = nullptr;
    decltype(&::FMOD_Studio_EventInstance_Set3DAttributes) EventInstance_Set3DAttributes = nullptr;
    decltype(&::FMOD_Studio_EventInstance_Start) EventInstance_Start = nullptr;
    decltype(&::FMOD_Studio_EventInstance_Stop) EventInstance_Stop = nullptr;
    decltype(&::FMOD_Studio_EventInstance_SetTimelinePosition) EventInstance_SetTimelinePosition = nullptr;
    decltype(&::FMOD_Studio_EventInstance_GetPlaybackState) EventInstance_GetPlaybackState = nullptr;
    decltype(&::FMOD_Studio_EventInstance_Release) EventInstance_Release = nullptr;
    decltype(&::FMOD_Studio_EventInstance_SetParameterValue) EventInstance_SetParameterValue = nullptr;
    decltype(&::FMOD_Studio_EventInstance_SetCallback) EventInstance_SetCallback = nullptr;
    decltype(&::FMOD_Studio_EventInstance_GetUserData) EventInstance_GetUserData = nullptr;
    decltype(&::FMOD_Studio_EventInstance_SetUserData) EventInstance_SetUserData = nullptr;

    bool load(std::string *error) {
        coreLibrary = dlopen("libfmod.so", RTLD_NOW | RTLD_GLOBAL);
        if (coreLibrary == nullptr) {
            *error = std::string("load libfmod.so: ") + (dlerror() ?: "unknown linker error");
            return false;
        }
        studioLibrary = dlopen("libfmodstudio.so", RTLD_NOW | RTLD_GLOBAL);
        if (studioLibrary == nullptr) {
            *error = std::string("load libfmodstudio.so: ") + (dlerror() ?: "unknown linker error");
            close();
            return false;
        }
#define LOAD_FMOD(library, member, symbol)                                      \
        member = reinterpret_cast<decltype(member)>(dlsym(library, #symbol));   \
        if (member == nullptr) {                                                 \
            *error = std::string("resolve ") + #symbol + ": " +            \
                (dlerror() ?: "symbol is absent from FMOD 1.10.11");          \
            close();                                                             \
            return false;                                                        \
        }
        LOAD_FMOD(coreLibrary, System_SetOutput, FMOD_System_SetOutput)
        LOAD_FMOD(coreLibrary, System_SetSoftwareFormat, FMOD_System_SetSoftwareFormat)
        LOAD_FMOD(coreLibrary, System_SetDSPBufferSize, FMOD_System_SetDSPBufferSize)
        LOAD_FMOD(coreLibrary, System_GetDSPBufferSize, FMOD_System_GetDSPBufferSize)
        LOAD_FMOD(coreLibrary, System_MixerSuspend, FMOD_System_MixerSuspend)
        LOAD_FMOD(coreLibrary, System_MixerResume, FMOD_System_MixerResume)
        LOAD_FMOD(coreLibrary, System_CreateDSP, FMOD_System_CreateDSP)
        LOAD_FMOD(coreLibrary, System_GetMasterChannelGroup, FMOD_System_GetMasterChannelGroup)
        LOAD_FMOD(coreLibrary, ChannelGroup_AddDSP, FMOD_ChannelGroup_AddDSP)
        LOAD_FMOD(coreLibrary, ChannelGroup_RemoveDSP, FMOD_ChannelGroup_RemoveDSP)
        LOAD_FMOD(coreLibrary, DSP_Release, FMOD_DSP_Release)
        LOAD_FMOD(coreLibrary, Sound_GetName, FMOD_Sound_GetName)
        LOAD_FMOD(studioLibrary, Studio_System_Create, FMOD_Studio_System_Create)
        LOAD_FMOD(studioLibrary, Studio_System_Initialize, FMOD_Studio_System_Initialize)
        LOAD_FMOD(studioLibrary, Studio_System_Release, FMOD_Studio_System_Release)
        LOAD_FMOD(studioLibrary, Studio_System_Update, FMOD_Studio_System_Update)
        LOAD_FMOD(studioLibrary, Studio_System_GetLowLevelSystem, FMOD_Studio_System_GetLowLevelSystem)
        LOAD_FMOD(studioLibrary, Studio_System_GetEventByID, FMOD_Studio_System_GetEventByID)
        LOAD_FMOD(studioLibrary, Studio_System_SetListenerAttributes, FMOD_Studio_System_SetListenerAttributes)
        LOAD_FMOD(studioLibrary, Studio_System_LoadBankFile, FMOD_Studio_System_LoadBankFile)
        LOAD_FMOD(studioLibrary, Bank_GetID, FMOD_Studio_Bank_GetID)
        LOAD_FMOD(studioLibrary, Studio_System_RegisterPlugin, FMOD_Studio_System_RegisterPlugin)
        LOAD_FMOD(studioLibrary, Studio_System_UnregisterPlugin, FMOD_Studio_System_UnregisterPlugin)
        LOAD_FMOD(studioLibrary, Studio_System_UnloadAll, FMOD_Studio_System_UnloadAll)
        LOAD_FMOD(studioLibrary, Studio_System_FlushSampleLoading, FMOD_Studio_System_FlushSampleLoading)
        LOAD_FMOD(studioLibrary, EventDescription_GetPath, FMOD_Studio_EventDescription_GetPath)
        LOAD_FMOD(studioLibrary, EventDescription_GetParameter, FMOD_Studio_EventDescription_GetParameter)
        LOAD_FMOD(studioLibrary, EventDescription_CreateInstance, FMOD_Studio_EventDescription_CreateInstance)
        LOAD_FMOD(studioLibrary, EventDescription_LoadSampleData, FMOD_Studio_EventDescription_LoadSampleData)
        LOAD_FMOD(studioLibrary, EventInstance_SetVolume, FMOD_Studio_EventInstance_SetVolume)
        LOAD_FMOD(studioLibrary, EventInstance_Set3DAttributes, FMOD_Studio_EventInstance_Set3DAttributes)
        LOAD_FMOD(studioLibrary, EventInstance_Start, FMOD_Studio_EventInstance_Start)
        LOAD_FMOD(studioLibrary, EventInstance_Stop, FMOD_Studio_EventInstance_Stop)
        LOAD_FMOD(studioLibrary, EventInstance_SetTimelinePosition, FMOD_Studio_EventInstance_SetTimelinePosition)
        LOAD_FMOD(studioLibrary, EventInstance_GetPlaybackState, FMOD_Studio_EventInstance_GetPlaybackState)
        LOAD_FMOD(studioLibrary, EventInstance_Release, FMOD_Studio_EventInstance_Release)
        LOAD_FMOD(studioLibrary, EventInstance_SetParameterValue, FMOD_Studio_EventInstance_SetParameterValue)
        LOAD_FMOD(studioLibrary, EventInstance_SetCallback, FMOD_Studio_EventInstance_SetCallback)
        LOAD_FMOD(studioLibrary, EventInstance_GetUserData, FMOD_Studio_EventInstance_GetUserData)
        LOAD_FMOD(studioLibrary, EventInstance_SetUserData, FMOD_Studio_EventInstance_SetUserData)
#undef LOAD_FMOD
        return true;
    }

    void close() {
        if (studioLibrary != nullptr) dlclose(studioLibrary);
        if (coreLibrary != nullptr) dlclose(coreLibrary);
        studioLibrary = nullptr;
        coreLibrary = nullptr;
    }
};

std::atomic<decltype(&::FMOD_Studio_EventInstance_GetUserData)> gGetEventUserData{nullptr};
std::atomic<decltype(&::FMOD_Sound_GetName)> gSoundGetName{nullptr};

template <typename T>
T readBufferValue(const std::uint8_t *bytes, std::size_t offset) {
    T result{};
    std::memcpy(&result, bytes + offset, sizeof(T));
    return result;
}

float finiteClamp(float value, float minimum, float maximum) {
    return std::clamp(value, minimum, maximum);
}

FMOD_3D_ATTRIBUTES attributesAt(float x, float y, float z) {
    FMOD_3D_ATTRIBUTES attributes{};
    attributes.position = {x, y, z};
    attributes.velocity = {0.0f, 0.0f, 0.0f};
    attributes.forward = {0.0f, 0.0f, -1.0f};
    attributes.up = {0.0f, 1.0f, 0.0f};
    return attributes;
}

bool sameGuid(const FMOD_GUID &left, const FMOD_GUID &right) {
    return std::memcmp(&left, &right, sizeof(FMOD_GUID)) == 0;
}

FMOD_RESULT F_CALLBACK passThroughRead(
    FMOD_DSP_STATE *, float *input, float *output, unsigned int length,
    int inputChannels, int *outputChannels) {
    if (input == nullptr || output == nullptr || inputChannels < 0) return FMOD_ERR_INVALID_PARAM;
    if (outputChannels != nullptr) *outputChannels = inputChannels;
    std::memcpy(output, input,
        static_cast<std::size_t>(length) * static_cast<std::size_t>(inputChannels) * sizeof(float));
    return FMOD_OK;
}

struct DistanceFilterState {
    float maxDistance = 100.0f;
    float frequency = 1000.0f;
    FMOD_DSP_PARAMETER_3DATTRIBUTES attributes{};
};

FMOD_RESULT F_CALLBACK distanceCreate(FMOD_DSP_STATE *dspState) {
    auto *state = static_cast<DistanceFilterState *>(
        FMOD_DSP_ALLOC(dspState, sizeof(DistanceFilterState)));
    if (state == nullptr) return FMOD_ERR_MEMORY;
    new (state) DistanceFilterState();
    dspState->plugindata = state;
    return FMOD_OK;
}

FMOD_RESULT F_CALLBACK distanceRelease(FMOD_DSP_STATE *dspState) {
    auto *state = static_cast<DistanceFilterState *>(dspState->plugindata);
    if (state != nullptr) {
        state->~DistanceFilterState();
        FMOD_DSP_FREE(dspState, state);
        dspState->plugindata = nullptr;
    }
    return FMOD_OK;
}

FMOD_RESULT F_CALLBACK distanceSetFloat(FMOD_DSP_STATE *dspState, int index, float value) {
    auto *state = static_cast<DistanceFilterState *>(dspState->plugindata);
    if (state == nullptr || !std::isfinite(value)) return FMOD_ERR_INVALID_PARAM;
    if (index == 0) state->maxDistance = value;
    else if (index == 1) state->frequency = value;
    else return FMOD_ERR_INVALID_PARAM;
    return FMOD_OK;
}

FMOD_RESULT F_CALLBACK distanceSetData(
    FMOD_DSP_STATE *dspState, int index, void *data, unsigned int length) {
    auto *state = static_cast<DistanceFilterState *>(dspState->plugindata);
    if (state == nullptr || index != 2 || data == nullptr ||
        length < sizeof(FMOD_DSP_PARAMETER_3DATTRIBUTES)) return FMOD_ERR_INVALID_PARAM;
    std::memcpy(&state->attributes, data, sizeof(state->attributes));
    return FMOD_OK;
}

FMOD_RESULT F_CALLBACK distanceGetFloat(
    FMOD_DSP_STATE *dspState, int index, float *value, char *) {
    auto *state = static_cast<DistanceFilterState *>(dspState->plugindata);
    if (state == nullptr || value == nullptr) return FMOD_ERR_INVALID_PARAM;
    if (index == 0) *value = state->maxDistance;
    else if (index == 1) *value = state->frequency;
    else return FMOD_ERR_INVALID_PARAM;
    return FMOD_OK;
}

FMOD_RESULT F_CALLBACK distanceGetData(
    FMOD_DSP_STATE *dspState, int index, void **value, unsigned int *length, char *) {
    auto *state = static_cast<DistanceFilterState *>(dspState->plugindata);
    if (state == nullptr || index != 2 || value == nullptr || length == nullptr) {
        return FMOD_ERR_INVALID_PARAM;
    }
    *value = &state->attributes;
    *length = sizeof(state->attributes);
    return FMOD_OK;
}

FMOD_RESULT F_CALLBACK compatibilityShouldProcess(
    FMOD_DSP_STATE *, FMOD_BOOL inputsIdle, unsigned int, FMOD_CHANNELMASK, int, FMOD_SPEAKERMODE) {
    return inputsIdle ? FMOD_ERR_DSP_DONTPROCESS : FMOD_OK;
}

FMOD_DSP_DESCRIPTION *distanceFilterDescription() {
    static FMOD_DSP_PARAMETER_DESC maxDistance{};
    static FMOD_DSP_PARAMETER_DESC frequency{};
    static FMOD_DSP_PARAMETER_DESC attributes{};
    static FMOD_DSP_PARAMETER_DESC *parameters[] = {&maxDistance, &frequency, &attributes};
    static FMOD_DSP_DESCRIPTION description{};
    static std::once_flag once;
    std::call_once(once, [] {
        FMOD_DSP_INIT_PARAMDESC_FLOAT(
            maxDistance, "Max Dist", "m",
            "Distance at which the filter reaches its target frequency.",
            0.0f, 10000.0f, 100.0f);
        FMOD_DSP_INIT_PARAMDESC_FLOAT(
            frequency, "Frequency", "Hz",
            "Low-pass target frequency at maximum distance.",
            10.0f, 22000.0f, 1000.0f);
        FMOD_DSP_INIT_PARAMDESC_DATA(
            attributes, "3D Attributes", "",
            "Source and listener transforms supplied by FMOD.",
            FMOD_DSP_PARAMETER_DATA_TYPE_3DATTRIBUTES);
        description.pluginsdkversion = FMOD_PLUGIN_SDK_VERSION;
        std::strncpy(description.name, "FMOD Distance Filter", sizeof(description.name) - 1);
        description.version = 0x00010000;
        description.numinputbuffers = 1;
        description.numoutputbuffers = 1;
        description.create = distanceCreate;
        description.release = distanceRelease;
        description.read = passThroughRead;
        description.numparameters = 3;
        description.paramdesc = parameters;
        description.setparameterfloat = distanceSetFloat;
        description.setparameterdata = distanceSetData;
        description.getparameterfloat = distanceGetFloat;
        description.getparameterdata = distanceGetData;
        description.shouldiprocess = compatibilityShouldProcess;
    });
    return &description;
}

struct GainState {
    float targetGain = 1.0f;
    float currentGain = 1.0f;
    int rampSamplesLeft = 0;
    bool invert = false;
};

float decibelsToLinear(float decibels) {
    return decibels <= -80.0f ? 0.0f : std::pow(10.0f, decibels / 20.0f);
}

FMOD_RESULT F_CALLBACK gainCreate(FMOD_DSP_STATE *dspState) {
    auto *state = static_cast<GainState *>(FMOD_DSP_ALLOC(dspState, sizeof(GainState)));
    if (state == nullptr) return FMOD_ERR_MEMORY;
    new (state) GainState();
    dspState->plugindata = state;
    return FMOD_OK;
}

FMOD_RESULT F_CALLBACK gainRelease(FMOD_DSP_STATE *dspState) {
    auto *state = static_cast<GainState *>(dspState->plugindata);
    if (state != nullptr) {
        state->~GainState();
        FMOD_DSP_FREE(dspState, state);
        dspState->plugindata = nullptr;
    }
    return FMOD_OK;
}

FMOD_RESULT F_CALLBACK gainReset(FMOD_DSP_STATE *dspState) {
    auto *state = static_cast<GainState *>(dspState->plugindata);
    if (state == nullptr) return FMOD_ERR_INVALID_PARAM;
    state->currentGain = state->targetGain;
    state->rampSamplesLeft = 0;
    return FMOD_OK;
}

FMOD_RESULT F_CALLBACK gainRead(
    FMOD_DSP_STATE *dspState, float *input, float *output, unsigned int length,
    int channels, int *outputChannels) {
    auto *state = static_cast<GainState *>(dspState->plugindata);
    if (state == nullptr || input == nullptr || output == nullptr || channels < 0) {
        return FMOD_ERR_INVALID_PARAM;
    }
    if (outputChannels != nullptr) *outputChannels = channels;
    float gain = state->currentGain;
    unsigned int frames = length;
    if (state->rampSamplesLeft > 0) {
        const float delta = (state->targetGain - gain) /
            static_cast<float>(state->rampSamplesLeft);
        while (frames > 0 && state->rampSamplesLeft > 0) {
            if (--state->rampSamplesLeft > 0) gain += delta;
            else gain = state->targetGain;
            for (int channel = 0; channel < channels; ++channel) *output++ = *input++ * gain;
            --frames;
        }
    }
    std::size_t remaining = static_cast<std::size_t>(frames) * static_cast<std::size_t>(channels);
    while (remaining-- > 0) *output++ = *input++ * gain;
    state->currentGain = gain;
    return FMOD_OK;
}

std::atomic<int> gGainFloatSetCount{0};
std::atomic<int> gGainBoolSetCount{0};
std::atomic<float> gGainLastDb{0.0f};
std::atomic<float> gDistortionLastLevel{0.5f};
std::atomic<int> gDistortionSetCount{0};

FMOD_RESULT F_CALLBACK gainSetFloat(FMOD_DSP_STATE *dspState, int index, float value) {
    auto *state = static_cast<GainState *>(dspState->plugindata);
    if (state == nullptr || index != 0 || !std::isfinite(value)) return FMOD_ERR_INVALID_PARAM;
    const float magnitude = decibelsToLinear(value);
    state->targetGain = state->invert ? -magnitude : magnitude;
    state->rampSamplesLeft = 256;
    gGainLastDb.store(value, std::memory_order_relaxed);
    gGainFloatSetCount.fetch_add(1, std::memory_order_relaxed);
    return FMOD_OK;
}

FMOD_RESULT F_CALLBACK gainGetFloat(
    FMOD_DSP_STATE *dspState, int index, float *value, char *) {
    auto *state = static_cast<GainState *>(dspState->plugindata);
    if (state == nullptr || index != 0 || value == nullptr) return FMOD_ERR_INVALID_PARAM;
    const float magnitude = std::fabs(state->targetGain);
    *value = magnitude <= 0.0f ? -80.0f : 20.0f * std::log10(magnitude);
    return FMOD_OK;
}

FMOD_RESULT F_CALLBACK gainSetBool(FMOD_DSP_STATE *dspState, int index, FMOD_BOOL value) {
    auto *state = static_cast<GainState *>(dspState->plugindata);
    if (state == nullptr || index != 1) return FMOD_ERR_INVALID_PARAM;
    const bool invert = value != 0;
    if (invert != state->invert) {
        state->targetGain = -state->targetGain;
        state->rampSamplesLeft = 256;
    }
    state->invert = invert;
    gGainBoolSetCount.fetch_add(1, std::memory_order_relaxed);
    return FMOD_OK;
}

FMOD_RESULT F_CALLBACK gainGetBool(
    FMOD_DSP_STATE *dspState, int index, FMOD_BOOL *value, char *) {
    auto *state = static_cast<GainState *>(dspState->plugindata);
    if (state == nullptr || index != 1 || value == nullptr) return FMOD_ERR_INVALID_PARAM;
    *value = state->invert ? 1 : 0;
    return FMOD_OK;
}

struct DistortionState { float level = 0.5f; };

FMOD_RESULT F_CALLBACK distortionCreate(FMOD_DSP_STATE *dspState) {
    auto *state = static_cast<DistortionState *>(
        FMOD_DSP_ALLOC(dspState, sizeof(DistortionState)));
    if (state == nullptr) return FMOD_ERR_MEMORY;
    new (state) DistortionState();
    dspState->plugindata = state;
    return FMOD_OK;
}

FMOD_RESULT F_CALLBACK distortionRelease(FMOD_DSP_STATE *dspState) {
    auto *state = static_cast<DistortionState *>(dspState->plugindata);
    if (state != nullptr) {
        state->~DistortionState();
        FMOD_DSP_FREE(dspState, state);
        dspState->plugindata = nullptr;
    }
    return FMOD_OK;
}

FMOD_RESULT F_CALLBACK distortionSetFloat(FMOD_DSP_STATE *dspState, int index, float value) {
    auto *state = static_cast<DistortionState *>(dspState->plugindata);
    if (state == nullptr || index != 0 || !std::isfinite(value)) return FMOD_ERR_INVALID_PARAM;
    state->level = std::clamp(value, 0.0f, 1.0f);
    gDistortionLastLevel.store(state->level, std::memory_order_relaxed);
    gDistortionSetCount.fetch_add(1, std::memory_order_relaxed);
    return FMOD_OK;
}

FMOD_RESULT F_CALLBACK distortionGetFloat(
    FMOD_DSP_STATE *dspState, int index, float *value, char *) {
    auto *state = static_cast<DistortionState *>(dspState->plugindata);
    if (state == nullptr || index != 0 || value == nullptr) return FMOD_ERR_INVALID_PARAM;
    *value = state->level;
    return FMOD_OK;
}

FMOD_RESULT F_CALLBACK distortionRead(
    FMOD_DSP_STATE *dspState, float *input, float *output, unsigned int length,
    int channels, int *outputChannels) {
    auto *state = static_cast<DistortionState *>(dspState->plugindata);
    if (state == nullptr || input == nullptr || output == nullptr || channels < 0) {
        return FMOD_ERR_INVALID_PARAM;
    }
    if (outputChannels != nullptr) *outputChannels = channels;
    // FMOD documents this effect as input amplification followed by a hard clip.
    // Mapping Level to the inverse clip threshold makes 0 neutral and approaches
    // full square-wave clipping at 1 while keeping the endpoint finite.
    const float level = std::clamp(state->level, 0.0f, 1.0f);
    const float clipThreshold = std::max(1.0e-4f, 1.0f - level);
    const float drive = 1.0f / clipThreshold;
    const std::size_t samples = static_cast<std::size_t>(length) *
        static_cast<std::size_t>(channels);
    for (std::size_t index = 0; index < samples; ++index) {
        const float sample = input[index];
        output[index] = std::isfinite(sample)
            ? std::clamp(sample * drive, -1.0f, 1.0f) : 0.0f;
    }
    return FMOD_OK;
}

FMOD_DSP_DESCRIPTION *gainDescription() {
    static FMOD_DSP_PARAMETER_DESC gain{};
    static FMOD_DSP_PARAMETER_DESC invert{};
    static FMOD_DSP_PARAMETER_DESC *parameters[] = {&gain, &invert};
    static FMOD_DSP_DESCRIPTION description{};
    static float gainMappingValues[] = {-80.0f, -50.0f, -30.0f, -10.0f, 10.0f};
    static float gainMappingScale[] = {0.0f, 2.0f, 4.0f, 7.0f, 11.0f};
    static std::once_flag once;
    std::call_once(once, [] {
        FMOD_DSP_INIT_PARAMDESC_FLOAT_WITH_MAPPING(
            gain, "Gain", "dB", "Gain in dB. -80 to 10. Default = 0", 0.0f,
            gainMappingValues, gainMappingScale);
        FMOD_DSP_INIT_PARAMDESC_BOOL(
            invert, "Invert", "", "Invert signal. Default = off", false, nullptr);
        description.pluginsdkversion = FMOD_PLUGIN_SDK_VERSION;
        std::strncpy(description.name, "FMOD Gain", sizeof(description.name) - 1);
        description.version = 0x00010000;
        description.numinputbuffers = 1;
        description.numoutputbuffers = 1;
        description.create = gainCreate;
        description.release = gainRelease;
        description.reset = gainReset;
        description.read = gainRead;
        description.numparameters = 2;
        description.paramdesc = parameters;
        description.setparameterfloat = gainSetFloat;
        description.setparameterbool = gainSetBool;
        description.getparameterfloat = gainGetFloat;
        description.getparameterbool = gainGetBool;
        description.shouldiprocess = compatibilityShouldProcess;
    });
    return &description;
}

FMOD_DSP_DESCRIPTION *distortionDescription() {
    static FMOD_DSP_PARAMETER_DESC level{};
    static FMOD_DSP_PARAMETER_DESC *parameters[] = {&level};
    static FMOD_DSP_DESCRIPTION description{};
    static std::once_flag once;
    std::call_once(once, [] {
        FMOD_DSP_INIT_PARAMDESC_FLOAT(
            level, "Level", "", "Distortion level. 0 to 1. Default = 0.5.",
            0.0f, 1.0f, 0.5f);
        description.pluginsdkversion = FMOD_PLUGIN_SDK_VERSION;
        std::strncpy(description.name, "FMOD Distortion", sizeof(description.name) - 1);
        description.version = 0x00010000;
        description.numinputbuffers = 1;
        description.numoutputbuffers = 1;
        description.create = distortionCreate;
        description.release = distortionRelease;
        description.read = distortionRead;
        description.numparameters = 1;
        description.paramdesc = parameters;
        description.setparameterfloat = distortionSetFloat;
        description.getparameterfloat = distortionGetFloat;
        description.shouldiprocess = compatibilityShouldProcess;
    });
    return &description;
}

struct MeterStats {
    std::atomic<std::uint64_t> frames{0};
    std::atomic<std::uint64_t> samples{0};
    std::atomic<std::uint64_t> sumSquaresQ{0};
    std::atomic<std::uint64_t> nonFinite{0};
    std::atomic<std::uint64_t> audibleCallbacks{0};
    std::atomic<std::uint32_t> peakBits{0};
    void reset() {
        frames.store(0, std::memory_order_relaxed);
        samples.store(0, std::memory_order_relaxed);
        sumSquaresQ.store(0, std::memory_order_relaxed);
        nonFinite.store(0, std::memory_order_relaxed);
        audibleCallbacks.store(0, std::memory_order_relaxed);
        peakBits.store(0, std::memory_order_relaxed);
    }
};

FMOD_RESULT F_CALLBACK meterRead(
    FMOD_DSP_STATE *dspState, float *input, float *output, unsigned int length,
    int inputChannels, int *outputChannels) {
    if (input == nullptr || output == nullptr || inputChannels < 0) return FMOD_ERR_INVALID_PARAM;
    if (outputChannels != nullptr) *outputChannels = inputChannels;
    const std::size_t sampleCount =
        static_cast<std::size_t>(length) * static_cast<std::size_t>(inputChannels);
    std::memcpy(output, input, sampleCount * sizeof(float));
    void *userData = nullptr;
    if (FMOD_DSP_GETUSERDATA(dspState, &userData) != FMOD_OK || userData == nullptr) return FMOD_OK;
    auto *stats = static_cast<MeterStats *>(userData);
    std::uint64_t finiteSamples = 0;
    std::uint64_t nonFinite = 0;
    std::uint64_t sumSquaresQ = 0;
    float peak = 0.0f;
    bool audible = false;
    for (std::size_t index = 0; index < sampleCount; ++index) {
        const float sample = input[index];
        if (!std::isfinite(sample)) { ++nonFinite; continue; }
        ++finiteSamples;
        const float magnitude = std::fabs(sample);
        peak = std::max(peak, magnitude);
        audible = audible || magnitude > kSilenceThreshold;
        const double clamped = std::min<double>(magnitude, 16.0);
        sumSquaresQ += static_cast<std::uint64_t>(clamped * clamped * 1.0e9);
    }
    stats->frames.fetch_add(length, std::memory_order_relaxed);
    stats->samples.fetch_add(finiteSamples, std::memory_order_relaxed);
    stats->sumSquaresQ.fetch_add(sumSquaresQ, std::memory_order_relaxed);
    stats->nonFinite.fetch_add(nonFinite, std::memory_order_relaxed);
    if (audible) stats->audibleCallbacks.fetch_add(1, std::memory_order_relaxed);
    std::uint32_t bits = 0;
    std::memcpy(&bits, &peak, sizeof(bits));
    std::uint32_t current = stats->peakBits.load(std::memory_order_relaxed);
    while (current < bits &&
           !stats->peakBits.compare_exchange_weak(current, bits, std::memory_order_relaxed)) {}
    return FMOD_OK;
}

struct EventCounters {
    static constexpr std::size_t kMaximumNames = 64;
    static constexpr std::size_t kNameBytes = 64;
    std::atomic<int> starts{0};
    std::atomic<int> soundPlayed{0};
    std::atomic<int> capturedNames{0};
    std::array<std::array<char, kNameBytes>, kMaximumNames> soundNames{};
    void reset() {
        starts.store(0, std::memory_order_relaxed);
        soundPlayed.store(0, std::memory_order_relaxed);
        capturedNames.store(0, std::memory_order_relaxed);
        for (auto &name : soundNames) name[0] = '\0';
    }
};

FMOD_RESULT F_CALLBACK eventCallback(
    FMOD_STUDIO_EVENT_CALLBACK_TYPE type, FMOD_STUDIO_EVENTINSTANCE *instance, void *parameters) {
    const auto getUserData = gGetEventUserData.load(std::memory_order_relaxed);
    if (getUserData == nullptr) return FMOD_OK;
    void *userData = nullptr;
    if (getUserData(instance, &userData) != FMOD_OK || userData == nullptr) return FMOD_OK;
    auto *counters = static_cast<EventCounters *>(userData);
    if ((type & FMOD_STUDIO_EVENT_CALLBACK_STARTED) != 0) {
        counters->starts.fetch_add(1, std::memory_order_relaxed);
    }
    if ((type & FMOD_STUDIO_EVENT_CALLBACK_SOUND_PLAYED) != 0) {
        counters->soundPlayed.fetch_add(1, std::memory_order_relaxed);
        const int slot = counters->capturedNames.fetch_add(1, std::memory_order_relaxed);
        const auto getName = gSoundGetName.load(std::memory_order_relaxed);
        if (slot >= 0 && static_cast<std::size_t>(slot) < EventCounters::kMaximumNames &&
            getName != nullptr && parameters != nullptr) {
            getName(static_cast<FMOD_SOUND *>(parameters), counters->soundNames[slot].data(),
                    static_cast<int>(EventCounters::kNameBytes));
        }
    }
    return FMOD_OK;
}

struct ControlState {
    std::int32_t flags = 0;
    float rpm = 800.0f;
    float engineThrottle = 0.0f;
    float boost = 0.0f;
    float bov = 0.0f;
    float bovDecay = 10.0f;
    float limiterDecay = 10.0f;
    float masterGain = 1.0f;
    float engineGain = 1.0f;
    float turboGain = 1.0f;
    float limiterGain = 1.0f;
    float shiftGain = 1.0f;
    float backfireGain = 1.0f;
    std::int32_t shiftDirection = 0;
    std::int64_t shiftSerial = 0;
    std::int64_t limiterSerial = 0;
    std::int64_t bovSerial = 0;
    std::int64_t backfireSerial = 0;
    float drivetrainSpeed = 0.0f;
    float transmissionThrottle = 0.0f;
    float transmissionGain = 1.0f;
};

struct RuntimeEvent {
    const EventSpec *spec = nullptr;
    FMOD_STUDIO_EVENTDESCRIPTION *description = nullptr;
    FMOD_STUDIO_EVENTINSTANCE *persistent = nullptr;
    std::array<FMOD_STUDIO_EVENTINSTANCE *, kOneShotPoolSize> pool{};
    std::size_t poolCount = 0;
    std::size_t cursor = 0;
    bool started = false;
    float appliedVolume = std::numeric_limits<float>::quiet_NaN();
    EventCounters counters;
};

struct CheckResult {
    EventKind kind = EventKind::Engine;
    const char *path = "";
    std::string detail;
    int starts = 0;
    int sounds = 0;
    std::uint64_t frames = 0;
    std::uint64_t samples = 0;
    std::uint64_t sumSquaresQ = 0;
    std::uint64_t nonFinite = 0;
    float peak = 0.0f;
    bool passed = false;
    std::string soundNames;
};

void requireSoundNameEvidence(
    CheckResult &result, const char *label,
    std::initializer_list<std::string_view> alternatives) {
    const bool found = std::any_of(
        alternatives.begin(), alternatives.end(), [&](std::string_view token) {
            return containsAsciiIgnoreCase(result.soundNames, token);
        });
    result.passed = result.passed && found;
    result.detail.append(found ? "; callback evidence=" : "; missing callback evidence=");
    result.detail.append(label);
}

enum class OutputMode { Device, NoSoundNrt };

class FmodRuntime {
public:
    explicit FmodRuntime(OutputMode outputMode = OutputMode::Device) : outputMode_(outputMode) {}
    ~FmodRuntime() { close(); }

    bool initialize();
    bool loadBanks(std::string_view profileId);
    bool update(const std::uint8_t *bytes, std::size_t capacity);
    std::string validateRenderedAudio();
    void requestValidationAbort() { validationAbort_.store(true, std::memory_order_relaxed); }
    bool suspendMixer();
    bool resumeMixer();
    std::string diagnostics() const;
    std::string lastError() const;
    void close();

private:
    RuntimeEvent &eventFor(EventKind kind) { return events_[static_cast<std::size_t>(kind)]; }
    bool registerCompatibilityPlugins();
    bool installMeter();
    bool resolveAndValidate(const EventSpec &, FMOD_STUDIO_EVENTDESCRIPTION **);
    bool createSelectedInstances();
    bool createAllowlistedInstance(RuntimeEvent &, FMOD_STUDIO_EVENTINSTANCE **);
    bool placeSelectedInstances();
    bool primeSelectedInstances();
    bool decodeControl(const std::uint8_t *, std::size_t, ControlState *);
    bool setParameter(FMOD_STUDIO_EVENTINSTANCE *, const char *, float, const EventSpec *);
    bool restart(FMOD_STUDIO_EVENTINSTANCE *, const EventSpec *);
    bool ensureStarted(RuntimeEvent &);
    bool applyVolumes(const ControlState &, bool);
    FMOD_STUDIO_EVENTINSTANCE *nextReusable(RuntimeEvent &);
    bool triggerOneShot(RuntimeEvent &, float, float);
    bool muteAndStopAll();
    bool renderUpdates(int, const std::atomic<bool> *);
    CheckResult captureScenario(RuntimeEvent &, const char *, float, float,
                                const std::atomic<bool> *, float, bool = true,
                                float = 1.0f, bool = true);
    CheckResult captureEngineTransition(
        RuntimeEvent &, EngineTransition, const std::atomic<bool> *, float);
    CheckResult captureEngineIgnition(RuntimeEvent &, const std::atomic<bool> *, float);
    CheckResult captureEngineShutdown(RuntimeEvent &, const std::atomic<bool> *, float);
    CheckResult captureTurboBov(RuntimeEvent &, const std::atomic<bool> *, float);
    static CheckResult combineChecks(const CheckResult &, const CheckResult &, const char *);
    std::string runValidation(const std::atomic<bool> *, std::chrono::steady_clock::time_point);
    static void appendCheckJson(std::ostringstream &, const CheckResult &);
    static std::uint64_t positiveSerialDelta(std::int64_t, std::int64_t);
    static std::uint64_t elapsedMilliseconds(std::chrono::steady_clock::time_point);
    static std::string jsonEscape(std::string_view);
    static std::string validationFailureJson(std::string_view, const ProfileSpec *, std::uint64_t);
    void releaseInstance(FMOD_STUDIO_EVENTINSTANCE *&);
    void unloadBanksUnlocked();
    void closeUnlocked();
    bool check(FMOD_RESULT, std::string_view);
    bool checkEventOperation(FMOD_RESULT, const char *, const char *);
    bool fail(std::string);

    mutable std::mutex mutex_;
    FmodApi api_;
    OutputMode outputMode_;
    FMOD_STUDIO_SYSTEM *studioSystem_ = nullptr;
    FMOD_SYSTEM *lowLevelSystem_ = nullptr;
    FMOD_STUDIO_BANK *stringsBank_ = nullptr;
    FMOD_STUDIO_BANK *commonBank_ = nullptr;
    FMOD_STUDIO_BANK *carBank_ = nullptr;
    FMOD_CHANNELGROUP *masterGroup_ = nullptr;
    FMOD_DSP *meterDsp_ = nullptr;
    FMOD_DSP_DESCRIPTION meterDescription_{};
    MeterStats meterStats_;
    const ProfileSpec *profile_ = nullptr;
    std::array<RuntimeEvent, static_cast<std::size_t>(EventKind::Count)> events_{};
    bool initialized_ = false;
    bool loaded_ = false;
    bool suspended_ = false;
    bool pluginsRegistered_ = false;
    bool meterAttached_ = false;
    unsigned int actualDspBufferFrames_ = 0;
    int actualDspBufferCount_ = 0;
    std::int64_t lastShiftSerial_ = 0;
    std::int64_t lastLimiterSerial_ = 0;
    std::int64_t lastBovSerial_ = 0;
    std::int64_t lastBackfireSerial_ = 0;
    std::uint64_t pendingLimiterPulses_ = 0;
    std::uint64_t acceptedLimiterEdges_ = 0;
    std::uint64_t deliveredLimiterPulses_ = 0;
    bool limiterPulseNeedsRearm_ = false;
    unsigned int limiterPulseZeroHoldUpdatesRemaining_ = 0;
    unsigned int limiterPulseCooldownUpdates_ = 0;
    std::uint64_t allowlistedInstanceCount_ = 0;
    std::uint64_t excludedInstantiationCount_ = 0;
    std::atomic<bool> validationAbort_{false};
    std::string lastError_;
};

bool FmodRuntime::initialize() {
    std::lock_guard<std::mutex> guard(mutex_);
    if (studioSystem_ != nullptr) return true;
    std::string dynamicLoadError;
    if (!api_.load(&dynamicLoadError)) return fail(std::move(dynamicLoadError));
    gGetEventUserData.store(api_.EventInstance_GetUserData, std::memory_order_relaxed);
    gSoundGetName.store(api_.Sound_GetName, std::memory_order_relaxed);
    if (!check(api_.Studio_System_Create(&studioSystem_, FMOD_VERSION), "create Studio system") ||
        !check(api_.Studio_System_GetLowLevelSystem(studioSystem_, &lowLevelSystem_),
               "get low-level system")) {
        closeUnlocked();
        return false;
    }
    if (outputMode_ == OutputMode::NoSoundNrt &&
        !check(api_.System_SetOutput(lowLevelSystem_, FMOD_OUTPUTTYPE_NOSOUND_NRT),
               "configure non-realtime no-sound output")) {
        closeUnlocked();
        return false;
    }
    const FMOD_STUDIO_INITFLAGS studioFlags = FMOD_STUDIO_INIT_SYNCHRONOUS_UPDATE;
    const FMOD_INITFLAGS coreFlags = outputMode_ == OutputMode::NoSoundNrt
        ? FMOD_INIT_STREAM_FROM_UPDATE : FMOD_INIT_NORMAL;
    const unsigned int dspBufferFrames = outputMode_ == OutputMode::NoSoundNrt
        ? kValidationDspBufferFrames : kDeviceDspBufferFrames;
    const int dspBufferCount = outputMode_ == OutputMode::NoSoundNrt
        ? kValidationDspBufferCount : kDeviceDspBufferCount;
    if (!check(api_.System_SetSoftwareFormat(lowLevelSystem_, 48000, FMOD_SPEAKERMODE_STEREO, 0),
               "configure 48 kHz stereo output") ||
        !check(api_.System_SetDSPBufferSize(
                   lowLevelSystem_, dspBufferFrames, dspBufferCount),
               "configure FMOD DSP buffers") ||
        !check(api_.Studio_System_Initialize(studioSystem_, 64, studioFlags, coreFlags, nullptr),
               "initialize Studio system") ||
        !check(api_.System_GetDSPBufferSize(
                   lowLevelSystem_, &actualDspBufferFrames_, &actualDspBufferCount_),
               "query actual FMOD DSP buffers")) {
        closeUnlocked();
        return false;
    }
    if (actualDspBufferFrames_ != dspBufferFrames || actualDspBufferCount_ != dspBufferCount) {
        std::ostringstream detail;
        detail << "FMOD applied unexpected DSP buffers " << actualDspBufferFrames_ << 'x'
               << actualDspBufferCount_ << "; requested " << dspBufferFrames << 'x'
               << dspBufferCount << '.';
        fail(detail.str());
        closeUnlocked();
        return false;
    }
    if (!registerCompatibilityPlugins() ||
        (outputMode_ == OutputMode::NoSoundNrt && !installMeter())) {
        closeUnlocked();
        return false;
    }
    initialized_ = true;
    return true;
}

bool FmodRuntime::registerCompatibilityPlugins() {
    if (!check(api_.Studio_System_RegisterPlugin(studioSystem_, distanceFilterDescription()),
               "register FMOD Distance Filter compatibility DSP") ||
        !check(api_.Studio_System_RegisterPlugin(studioSystem_, gainDescription()),
               "register FMOD Gain compatibility DSP") ||
        !check(api_.Studio_System_RegisterPlugin(studioSystem_, distortionDescription()),
               "register FMOD Distortion compatibility DSP")) return false;
    pluginsRegistered_ = true;
    return true;
}

bool FmodRuntime::installMeter() {
    std::memset(&meterDescription_, 0, sizeof(meterDescription_));
    meterDescription_.pluginsdkversion = FMOD_PLUGIN_SDK_VERSION;
    std::strncpy(meterDescription_.name, "BYD Render Meter", sizeof(meterDescription_.name) - 1);
    meterDescription_.version = 0x00010000;
    meterDescription_.numinputbuffers = 1;
    meterDescription_.numoutputbuffers = 1;
    meterDescription_.read = meterRead;
    meterDescription_.userdata = &meterStats_;
    if (!check(api_.System_CreateDSP(lowLevelSystem_, &meterDescription_, &meterDsp_),
               "create render meter DSP") ||
        !check(api_.System_GetMasterChannelGroup(lowLevelSystem_, &masterGroup_),
               "get master channel group") ||
        !check(api_.ChannelGroup_AddDSP(masterGroup_, FMOD_CHANNELCONTROL_DSP_TAIL, meterDsp_),
               "attach render meter DSP")) return false;
    meterAttached_ = true;
    return true;
}

bool FmodRuntime::loadBanks(std::string_view profileId) {
    std::lock_guard<std::mutex> guard(mutex_);
    if (!initialized_) return fail("FMOD Studio system is not initialized.");
    const ProfileSpec *profile = findProfile(profileId);
    if (profile == nullptr) return fail(std::string("Unknown FMOD car profile: ") + std::string(profileId));
    if (loaded_ && profile_ == profile) return true;
    if (loaded_) unloadBanksUnlocked();
    profile_ = profile;
    gGainFloatSetCount.store(0, std::memory_order_relaxed);
    gGainBoolSetCount.store(0, std::memory_order_relaxed);
    gGainLastDb.store(0.0f, std::memory_order_relaxed);
    gDistortionSetCount.store(0, std::memory_order_relaxed);
    gDistortionLastLevel.store(0.5f, std::memory_order_relaxed);
    if (!check(api_.Studio_System_LoadBankFile(
                   studioSystem_, kStringsBankPath, FMOD_STUDIO_LOAD_BANK_NORMAL, &stringsBank_),
               "load common.strings.bank") ||
        !check(api_.Studio_System_LoadBankFile(
                   studioSystem_, kCommonBankPath, FMOD_STUDIO_LOAD_BANK_NORMAL, &commonBank_),
               "load common.bank") ||
        !check(api_.Studio_System_LoadBankFile(
                   studioSystem_, profile->bankAssetPath, FMOD_STUDIO_LOAD_BANK_NORMAL, &carBank_),
               "load selected car bank")) {
        unloadBanksUnlocked();
        return false;
    }
    FMOD_GUID loadedBankGuid{};
    if (!check(api_.Bank_GetID(carBank_, &loadedBankGuid), "read selected car bank GUID") ||
        !sameGuid(loadedBankGuid, profile->bankGuid)) {
        if (lastError_.empty()) {
            fail(std::string("Selected bank GUID does not match the audited profile contract for ") +
                 profile->id + ".");
        }
        unloadBanksUnlocked();
        return false;
    }
    for (std::size_t index = 0; index < profile->eventCount; ++index) {
        const EventSpec &spec = profile->events[index];
        RuntimeEvent &event = eventFor(spec.kind);
        event.spec = &spec;
        if (!resolveAndValidate(spec, &event.description) ||
            !check(api_.EventDescription_LoadSampleData(event.description),
                   "preload allowlisted event sample data")) {
            unloadBanksUnlocked();
            return false;
        }
    }
    if (!check(api_.Studio_System_FlushSampleLoading(studioSystem_),
               "flush selected event sample loading") ||
        !createSelectedInstances() || !placeSelectedInstances() || !primeSelectedInstances()) {
        unloadBanksUnlocked();
        return false;
    }
    loaded_ = true;
    lastShiftSerial_ = 0;
    lastLimiterSerial_ = 0;
    lastBovSerial_ = 0;
    lastBackfireSerial_ = 0;
    pendingLimiterPulses_ = 0;
    acceptedLimiterEdges_ = 0;
    deliveredLimiterPulses_ = 0;
    limiterPulseNeedsRearm_ = false;
    limiterPulseZeroHoldUpdatesRemaining_ = 0;
    limiterPulseCooldownUpdates_ = 0;
    lastError_.clear();
    return true;
}

bool FmodRuntime::resolveAndValidate(
    const EventSpec &spec, FMOD_STUDIO_EVENTDESCRIPTION **description) {
    if (!check(api_.Studio_System_GetEventByID(studioSystem_, &spec.guid, description),
               "resolve allowlisted event GUID")) return false;
    char path[512]{};
    int retrieved = 0;
    const FMOD_RESULT pathResult = api_.EventDescription_GetPath(
        *description, path, static_cast<int>(sizeof(path)), &retrieved);
    if (pathResult != FMOD_OK &&
        !(pathResult == FMOD_ERR_EVENT_NOTFOUND && !profile_->hasEventPathMetadata)) {
        return check(pathResult, "read allowlisted event path");
    }
    if (pathResult == FMOD_OK && std::strcmp(path, spec.path) != 0) {
        return fail(std::string("GUID resolved to unexpected event path ") + path +
                    "; expected " + spec.path);
    }
    for (std::size_t index = 0; index < spec.parameterCount; ++index) {
        const char *parameterName = spec.parameters[index];
        FMOD_STUDIO_PARAMETER_DESCRIPTION parameter{};
        if (!check(api_.EventDescription_GetParameter(*description, parameterName, &parameter),
                   "validate allowlisted event parameter")) return false;
        if (parameter.name == nullptr || std::strcmp(parameter.name, parameterName) != 0 ||
            !std::isfinite(parameter.minimum) || !std::isfinite(parameter.maximum) ||
            parameter.maximum <= parameter.minimum) {
            return fail(std::string("Invalid parameter contract for ") + spec.path + "." +
                        parameterName);
        }
    }
    return true;
}

bool FmodRuntime::createSelectedInstances() {
    for (auto &event : events_) {
        if (event.spec == nullptr) continue;
        if (event.spec->kind == EventKind::Shifts || event.spec->kind == EventKind::Backfire) {
            event.poolCount = kOneShotPoolSize;
            for (std::size_t index = 0; index < event.poolCount; ++index) {
                if (!createAllowlistedInstance(event, &event.pool[index])) return false;
            }
        } else if (!createAllowlistedInstance(event, &event.persistent)) {
            return false;
        }
    }
    return true;
}

bool FmodRuntime::createAllowlistedInstance(
    RuntimeEvent &event, FMOD_STUDIO_EVENTINSTANCE **instance) {
    if (!check(api_.EventDescription_CreateInstance(event.description, instance),
               "create allowlisted event instance")) return false;
    ++allowlistedInstanceCount_;
    if (outputMode_ == OutputMode::NoSoundNrt) {
        if (!check(api_.EventInstance_SetUserData(*instance, &event.counters),
                   "attach event callback counters") ||
            !check(api_.EventInstance_SetCallback(
                       *instance, eventCallback,
                       FMOD_STUDIO_EVENT_CALLBACK_STARTED | FMOD_STUDIO_EVENT_CALLBACK_SOUND_PLAYED),
                   "register event render callbacks")) return false;
    }
    return true;
}

bool FmodRuntime::placeSelectedInstances() {
    auto emitter = attributesAt(0.0f, 0.3266f, 1.14595f);
    auto rearEmitter = attributesAt(0.0f, 0.3266f, -2.01905f);
    auto listener = attributesAt(-0.377668f, 1.10719f, -0.162679f);
    if (!check(api_.Studio_System_SetListenerAttributes(studioSystem_, 0, &listener),
               "set cockpit listener")) return false;
    for (auto &event : events_) {
        if (event.spec == nullptr) continue;
        FMOD_3D_ATTRIBUTES &attributes = event.spec->kind == EventKind::Backfire
            ? rearEmitter : emitter;
        if (event.persistent != nullptr &&
            !check(api_.EventInstance_Set3DAttributes(event.persistent, &attributes),
                   "place persistent allowlisted event")) return false;
        for (std::size_t index = 0; index < event.poolCount; ++index) {
            if (!check(api_.EventInstance_Set3DAttributes(event.pool[index], &attributes),
                       "place pooled allowlisted event")) return false;
        }
    }
    return true;
}

bool FmodRuntime::primeSelectedInstances() {
    for (auto &event : events_) {
        if (event.spec == nullptr) continue;
        if (event.spec->kind == EventKind::Engine) {
            if (!setParameter(event.persistent, "rpms", profile_->idleRpm, event.spec) ||
                !setParameter(event.persistent, "throttle", 0.0f, event.spec)) return false;
        } else if (event.spec->kind == EventKind::Turbo) {
            if (!setParameter(event.persistent, "boost", 0.0f, event.spec) ||
                !setParameter(event.persistent, "bov", 0.0f, event.spec) ||
                !setParameter(event.persistent, "bov_decay", 10.0f, event.spec)) return false;
        } else if (event.spec->kind == EventKind::Limiter) {
            if (!setParameter(event.persistent, "decay", 10.0f, event.spec)) return false;
        } else if (event.spec->kind == EventKind::Transmission) {
            if (!setParameter(event.persistent, "drivetrain_speed", 0.0f, event.spec) ||
                !setParameter(event.persistent, "throttle", 0.0f, event.spec)) return false;
        } else {
            const char *parameter = event.spec->kind == EventKind::Shifts ? "state" : "throttle";
            const float value = event.spec->kind == EventKind::Shifts
                ? 1.0f : event.spec->triggerValue;
            for (std::size_t index = 0; index < event.poolCount; ++index) {
                if (!setParameter(event.pool[index], parameter, value, event.spec) ||
                    !check(api_.EventInstance_SetVolume(event.pool[index], 0.0f),
                           "mute pooled event before first state")) return false;
            }
            event.appliedVolume = 0.0f;
            continue;
        }
        // Deliberately do not start persistent graphs here. Starting engine_int while muted
        // consumes embedded ignition samples in banks such as the Supra.
        if (!check(api_.EventInstance_SetVolume(event.persistent, 0.0f),
                   "mute persistent event before first state")) return false;
        event.appliedVolume = 0.0f;
    }
    return check(api_.Studio_System_Update(studioSystem_), "prime FMOD bank metadata");
}

bool FmodRuntime::update(const std::uint8_t *bytes, std::size_t capacity) {
    std::lock_guard<std::mutex> guard(mutex_);
    if (!loaded_) return fail("FMOD banks are not loaded.");
    ControlState control{};
    if (!decodeControl(bytes, capacity, &control)) return false;
    const bool audioEnabled = (control.flags & kAudioEnabled) != 0;
    const bool bovEdge = control.bovSerial > lastBovSerial_;
    const std::uint64_t shiftEdges = positiveSerialDelta(control.shiftSerial, lastShiftSerial_);
    const std::uint64_t limiterEdges =
        positiveSerialDelta(control.limiterSerial, lastLimiterSerial_);
    const std::uint64_t backfireEdges = positiveSerialDelta(control.backfireSerial, lastBackfireSerial_);

    RuntimeEvent &engine = eventFor(EventKind::Engine);
    if (!setParameter(engine.persistent, "rpms", control.rpm, engine.spec) ||
        !setParameter(engine.persistent, "throttle",
                      audioEnabled ? control.engineThrottle : 0.0f, engine.spec)) return false;
    RuntimeEvent &turbo = eventFor(EventKind::Turbo);
    if (turbo.spec != nullptr &&
        (!setParameter(turbo.persistent, "boost", control.boost, turbo.spec) ||
         !setParameter(turbo.persistent, "bov",
                       bovEdge ? std::max(1.0f, control.bov) : control.bov, turbo.spec) ||
         !setParameter(turbo.persistent, "bov_decay", control.bovDecay, turbo.spec))) return false;
    RuntimeEvent &limiter = eventFor(EventKind::Limiter);
    if (limiter.spec != nullptr) {
        const bool limiterEnabled = audioEnabled && (control.flags & kLimiterEnabled) != 0;
        if (!limiterEnabled) {
            // Disabled/muted time must not become a stale burst when the user re-enables the row.
            pendingLimiterPulses_ = 0;
            limiterPulseNeedsRearm_ = false;
            limiterPulseZeroHoldUpdatesRemaining_ = 0;
            limiterPulseCooldownUpdates_ = 0;
            if (!setParameter(
                    limiter.persistent, "decay", control.limiterDecay, limiter.spec)) return false;
        } else {
            if (limiterEdges > std::numeric_limits<std::uint64_t>::max() -
                                   pendingLimiterPulses_ ||
                limiterEdges > std::numeric_limits<std::uint64_t>::max() -
                                   acceptedLimiterEdges_) {
                return fail("FMOD limiter pulse backlog overflowed.");
            }
            pendingLimiterPulses_ += limiterEdges;
            acceptedLimiterEdges_ += limiterEdges;

            // `decay=0` is the authored pulse edge. Hold it for three 400 Hz control ticks
            // (7.5 ms), spanning multiple 64-frame mixer blocks, so the synchronous Studio graph
            // evaluates it at least once. The remaining five ticks rearm/cool down, preserving
            // one pulse start every eight ticks (50 Hz). Delayed serial edges queue rather than
            // coalescing even though continuous decay may do so.
            if (limiterPulseNeedsRearm_) {
                if (limiterPulseZeroHoldUpdatesRemaining_ > 0) {
                    if (!setParameter(
                            limiter.persistent, "decay", 0.0f, limiter.spec)) return false;
                    --limiterPulseZeroHoldUpdatesRemaining_;
                } else {
                    if (!setParameter(
                            limiter.persistent, "decay",
                            std::max(control.limiterDecay, kLimiterPulseRearmSeconds),
                            limiter.spec)) return false;
                    limiterPulseNeedsRearm_ = false;
                }
                if (limiterPulseCooldownUpdates_ > 0) --limiterPulseCooldownUpdates_;
            } else if (limiterPulseCooldownUpdates_ > 0) {
                if (!setParameter(
                        limiter.persistent, "decay",
                        std::max(control.limiterDecay, kLimiterPulseRearmSeconds),
                        limiter.spec)) return false;
                --limiterPulseCooldownUpdates_;
            } else if (pendingLimiterPulses_ > 0) {
                if (!setParameter(limiter.persistent, "decay", 0.0f, limiter.spec)) return false;
                --pendingLimiterPulses_;
                ++deliveredLimiterPulses_;
                limiterPulseNeedsRearm_ = true;
                limiterPulseZeroHoldUpdatesRemaining_ = kLimiterPulseZeroHoldUpdates - 1;
                limiterPulseCooldownUpdates_ = kControlUpdatesPerLimiterPulse - 1;
            } else if (!setParameter(
                           limiter.persistent, "decay", control.limiterDecay,
                           limiter.spec)) {
                return false;
            }
        }
    }
    RuntimeEvent &transmission = eventFor(EventKind::Transmission);
    if (transmission.spec != nullptr &&
        (!setParameter(transmission.persistent, "drivetrain_speed", control.drivetrainSpeed,
                       transmission.spec) ||
         !setParameter(transmission.persistent, "throttle", control.transmissionThrottle,
                       transmission.spec))) return false;

    // Start authored persistent graphs on the first enabled control state, never at bank load.
    // Stop only after the controller finishes its RPM/fade shutdown and clears audioEnabled.
    for (auto &event : events_) {
        if (event.spec == nullptr || event.persistent == nullptr) continue;
        if (audioEnabled) {
            if (!ensureStarted(event)) return false;
        } else if (event.started) {
            if (!check(api_.EventInstance_Stop(event.persistent, FMOD_STUDIO_STOP_ALLOWFADEOUT),
                       "stop persistent event after engine-off lifecycle")) return false;
            event.started = false;
        }
    }
    if (!applyVolumes(control, audioEnabled)) return false;

    RuntimeEvent &shifts = eventFor(EventKind::Shifts);
    if (shifts.spec != nullptr && audioEnabled && (control.flags & kShiftEnabled) != 0 &&
        control.shiftDirection != 0) {
        for (std::uint64_t edge = 0; edge < shiftEdges; ++edge) {
            if (!triggerOneShot(shifts, control.shiftDirection > 0 ? 1.0f : 0.0f,
                                control.masterGain * control.shiftGain)) return false;
        }
    }
    RuntimeEvent &backfire = eventFor(EventKind::Backfire);
    if (backfire.spec != nullptr && audioEnabled && (control.flags & kBackfireEnabled) != 0) {
        for (std::uint64_t edge = 0; edge < backfireEdges; ++edge) {
            if (!triggerOneShot(backfire, backfire.spec->triggerValue,
                                control.masterGain * control.backfireGain)) return false;
        }
    }
    lastShiftSerial_ = control.shiftSerial;
    lastLimiterSerial_ = control.limiterSerial;
    lastBovSerial_ = control.bovSerial;
    lastBackfireSerial_ = control.backfireSerial;
    if (!check(api_.Studio_System_Update(studioSystem_), "update FMOD Studio")) return false;
    lastError_.clear();
    return true;
}

bool FmodRuntime::decodeControl(
    const std::uint8_t *bytes, std::size_t capacity, ControlState *control) {
    if (bytes == nullptr || capacity < kControlBufferBytes) {
        return fail("FMOD control buffer is null or smaller than 112 bytes.");
    }
    const auto schema = readBufferValue<std::int32_t>(bytes, kSchemaOffset);
    if (schema != kControlSchemaVersion) {
        return fail("Unsupported FMOD control buffer schema " + std::to_string(schema) + ".");
    }
    control->flags = readBufferValue<std::int32_t>(bytes, kEnabledMaskOffset);
    if ((control->flags & ~kKnownEnabledMask) != 0) {
        return fail("FMOD control buffer contains unknown enable flags.");
    }
    control->rpm = readBufferValue<float>(bytes, kRpmOffset);
    control->engineThrottle = readBufferValue<float>(bytes, kEngineThrottleOffset);
    control->boost = readBufferValue<float>(bytes, kBoostOffset);
    control->bov = readBufferValue<float>(bytes, kBovOffset);
    control->bovDecay = readBufferValue<float>(bytes, kBovDecayOffset);
    control->limiterDecay = readBufferValue<float>(bytes, kLimiterDecayOffset);
    control->masterGain = readBufferValue<float>(bytes, kMasterGainOffset);
    control->engineGain = readBufferValue<float>(bytes, kEngineGainOffset);
    control->turboGain = readBufferValue<float>(bytes, kTurboGainOffset);
    control->limiterGain = readBufferValue<float>(bytes, kLimiterGainOffset);
    control->shiftGain = readBufferValue<float>(bytes, kShiftGainOffset);
    control->backfireGain = readBufferValue<float>(bytes, kBackfireGainOffset);
    control->shiftDirection = readBufferValue<std::int32_t>(bytes, kShiftDirectionOffset);
    control->shiftSerial = readBufferValue<std::int64_t>(bytes, kShiftSerialOffset);
    control->limiterSerial = readBufferValue<std::int64_t>(bytes, kLimiterSerialOffset);
    control->bovSerial = readBufferValue<std::int64_t>(bytes, kBovSerialOffset);
    control->backfireSerial = readBufferValue<std::int64_t>(bytes, kBackfireSerialOffset);
    control->drivetrainSpeed = readBufferValue<float>(bytes, kDrivetrainSpeedOffset);
    control->transmissionThrottle = readBufferValue<float>(bytes, kTransmissionThrottleOffset);
    control->transmissionGain = readBufferValue<float>(bytes, kTransmissionGainOffset);
    if (control->shiftSerial < 0 || control->limiterSerial < 0 || control->bovSerial < 0 ||
        control->backfireSerial < 0) return fail("FMOD event serials must be non-negative.");
    const float *finiteValues[] = {
        &control->rpm, &control->engineThrottle, &control->boost, &control->bov,
        &control->bovDecay, &control->limiterDecay, &control->masterGain,
        &control->engineGain, &control->turboGain, &control->limiterGain,
        &control->shiftGain, &control->backfireGain, &control->drivetrainSpeed,
        &control->transmissionThrottle, &control->transmissionGain,
    };
    for (const float *value : finiteValues) {
        if (!std::isfinite(*value)) return fail("FMOD control buffer contains a non-finite value.");
    }
    if (control->shiftDirection < -1 || control->shiftDirection > 1) {
        return fail("FMOD shift direction must be -1, 0, or 1.");
    }
    control->rpm = finiteClamp(control->rpm, 1.0f, 20000.0f);
    control->engineThrottle = finiteClamp(control->engineThrottle, 0.0f, 1.0f);
    control->boost = finiteClamp(control->boost, 0.0f, 1.0f);
    control->bov = finiteClamp(control->bov, 0.0f, 2.0f);
    control->bovDecay = finiteClamp(control->bovDecay, 0.0f, 10.0f);
    control->limiterDecay = finiteClamp(control->limiterDecay, 0.0f, 10.0f);
    control->masterGain = finiteClamp(control->masterGain, 0.0f, 8.0f);
    control->engineGain = finiteClamp(control->engineGain, 0.0f, 8.0f);
    control->turboGain = finiteClamp(control->turboGain, 0.0f, 8.0f);
    control->limiterGain = finiteClamp(control->limiterGain, 0.0f, 8.0f);
    control->shiftGain = finiteClamp(control->shiftGain, 0.0f, 8.0f);
    control->backfireGain = finiteClamp(control->backfireGain, 0.0f, 8.0f);
    control->drivetrainSpeed = finiteClamp(control->drivetrainSpeed, -500.0f, 500.0f);
    control->transmissionThrottle = finiteClamp(control->transmissionThrottle, 0.0f, 1.0f);
    control->transmissionGain = finiteClamp(control->transmissionGain, 0.0f, 8.0f);
    return true;
}

std::uint64_t FmodRuntime::positiveSerialDelta(std::int64_t current, std::int64_t previous) {
    if (current <= previous) return 0;
    return static_cast<std::uint64_t>(current) - static_cast<std::uint64_t>(previous);
}

bool FmodRuntime::setParameter(
    FMOD_STUDIO_EVENTINSTANCE *instance, const char *parameter, float value,
    const EventSpec *spec) {
    if (instance == nullptr || spec == nullptr) return fail("Missing allowlisted event instance.");
    const FMOD_RESULT result = api_.EventInstance_SetParameterValue(instance, parameter, value);
    if (result == FMOD_OK) return true;
    std::string operation("set ");
    operation.append(spec->path).append(".").append(parameter);
    return check(result, operation);
}

bool FmodRuntime::restart(FMOD_STUDIO_EVENTINSTANCE *instance, const EventSpec *spec) {
    FMOD_RESULT result = api_.EventInstance_SetTimelinePosition(instance, 0);
    if (result != FMOD_OK) return checkEventOperation(result, "rewind ", spec->path);
    result = api_.EventInstance_Start(instance);
    return result == FMOD_OK || checkEventOperation(result, "start ", spec->path);
}

bool FmodRuntime::ensureStarted(RuntimeEvent &event) {
    if (event.started) return true;
    if (!restart(event.persistent, event.spec)) return false;
    event.started = true;
    return true;
}

bool FmodRuntime::applyVolumes(const ControlState &control, bool audioEnabled) {
    const auto volume = [&](EventKind kind) {
        std::int32_t flag = 0;
        float gain = 1.0f;
        switch (kind) {
            case EventKind::Engine: flag = kEngineEnabled; gain = control.engineGain; break;
            case EventKind::Turbo: flag = kTurboEnabled; gain = control.turboGain; break;
            case EventKind::Limiter: flag = kLimiterEnabled; gain = control.limiterGain; break;
            case EventKind::Shifts: flag = kShiftEnabled; gain = control.shiftGain; break;
            case EventKind::Backfire: flag = kBackfireEnabled; gain = control.backfireGain; break;
            case EventKind::Transmission:
                flag = kTransmissionEnabled; gain = control.transmissionGain; break;
            case EventKind::Count: break;
        }
        return audioEnabled && (control.flags & flag) != 0
            ? finiteClamp(control.masterGain * gain, 0.0f, 8.0f) : 0.0f;
    };
    for (auto &event : events_) {
        if (event.spec == nullptr) continue;
        const float eventVolume = volume(event.spec->kind);
        if (event.appliedVolume == eventVolume) continue;
        if (event.persistent != nullptr &&
            !check(api_.EventInstance_SetVolume(event.persistent, eventVolume),
                   "set persistent event volume")) return false;
        for (std::size_t index = 0; index < event.poolCount; ++index) {
            if (!check(api_.EventInstance_SetVolume(event.pool[index], eventVolume),
                       "set pooled event volume")) return false;
        }
        event.appliedVolume = eventVolume;
    }
    return true;
}

FMOD_STUDIO_EVENTINSTANCE *FmodRuntime::nextReusable(RuntimeEvent &event) {
    for (std::size_t offset = 0; offset < event.poolCount; ++offset) {
        const std::size_t index = (event.cursor + offset) % event.poolCount;
        FMOD_STUDIO_PLAYBACK_STATE state = FMOD_STUDIO_PLAYBACK_STOPPED;
        if (api_.EventInstance_GetPlaybackState(event.pool[index], &state) == FMOD_OK &&
            (state == FMOD_STUDIO_PLAYBACK_STOPPED || state == FMOD_STUDIO_PLAYBACK_STOPPING)) {
            event.cursor = (index + 1) % event.poolCount;
            return event.pool[index];
        }
    }
    auto *instance = event.pool[event.cursor];
    event.cursor = (event.cursor + 1) % event.poolCount;
    api_.EventInstance_Stop(instance, FMOD_STUDIO_STOP_IMMEDIATE);
    return instance;
}

bool FmodRuntime::triggerOneShot(RuntimeEvent &event, float parameterValue, float gain) {
    auto *instance = nextReusable(event);
    const char *parameter = event.spec->kind == EventKind::Shifts ? "state" : "throttle";
    return setParameter(instance, parameter, parameterValue, event.spec) &&
        check(api_.EventInstance_SetVolume(instance, finiteClamp(gain, 0.0f, 8.0f)),
              "set one-shot event volume") && restart(instance, event.spec);
}

bool FmodRuntime::muteAndStopAll() {
    for (auto &event : events_) {
        if (event.spec == nullptr) continue;
        if (event.persistent != nullptr) {
            if (!check(api_.EventInstance_SetVolume(event.persistent, 0.0f),
                       "mute validation event") ||
                !check(api_.EventInstance_Stop(event.persistent, FMOD_STUDIO_STOP_IMMEDIATE),
                       "stop validation event")) return false;
            event.started = false;
        }
        for (std::size_t index = 0; index < event.poolCount; ++index) {
            if (!check(api_.EventInstance_SetVolume(event.pool[index], 0.0f),
                       "mute validation one-shot") ||
                !check(api_.EventInstance_Stop(event.pool[index], FMOD_STUDIO_STOP_IMMEDIATE),
                       "stop validation one-shot")) return false;
        }
        event.appliedVolume = 0.0f;
    }
    return true;
}

bool FmodRuntime::renderUpdates(int count, const std::atomic<bool> *abort) {
    for (int index = 0; index < count; ++index) {
        if (abort != nullptr && abort->load(std::memory_order_relaxed)) return false;
        if (!check(api_.Studio_System_Update(studioSystem_), "render NRT validation buffer")) {
            return false;
        }
    }
    return true;
}

CheckResult FmodRuntime::captureScenario(
    RuntimeEvent &event, const char *detail, float valueA, float valueB,
    const std::atomic<bool> *abort, float baselinePeak, bool warmPersistent,
    float eventVolume, bool expectAudible) {
    CheckResult result{};
    result.kind = event.spec->kind;
    result.path = event.spec->path;
    result.detail = detail;
    if (!muteAndStopAll() || !renderUpdates(3, abort)) return result;
    event.counters.reset();
    meterStats_.reset();
    const auto configure = [&](FMOD_STUDIO_EVENTINSTANCE *instance) {
        bool configured = check(api_.EventInstance_SetVolume(instance, eventVolume),
                                "unmute validation event");
        switch (event.spec->kind) {
            case EventKind::Engine:
                return configured && setParameter(instance, "rpms", valueA, event.spec) &&
                    setParameter(instance, "throttle", valueB, event.spec);
            case EventKind::Turbo:
                return configured && setParameter(instance, "boost", valueA, event.spec) &&
                    setParameter(instance, "bov", valueB, event.spec) &&
                    setParameter(instance, "bov_decay", valueB > 0.0f ? 0.0f : 10.0f,
                                 event.spec);
            case EventKind::Limiter:
                return configured && setParameter(instance, "decay", 0.0f, event.spec);
            case EventKind::Shifts:
                return configured && setParameter(instance, "state", valueA, event.spec);
            case EventKind::Backfire:
                return configured && setParameter(
                    instance, "throttle", event.spec->triggerValue, event.spec);
            case EventKind::Transmission:
                return configured &&
                    setParameter(instance, "drivetrain_speed", valueA, event.spec) &&
                    setParameter(instance, "throttle", valueB, event.spec);
            case EventKind::Count: return false;
        }
        return false;
    };
    const bool oneShot = event.spec->kind == EventKind::Shifts ||
        event.spec->kind == EventKind::Backfire;
    bool configured = true;
    if (oneShot) {
        // Some authored one-shots deliberately use trigger chance (Huracan backfire is 51%).
        // Exercise every preallocated instance so the proof observes the graph without
        // changing that authored randomness in the actual runtime.
        for (std::size_t attempt = 0; configured && attempt < event.poolCount; ++attempt) {
            FMOD_STUDIO_EVENTINSTANCE *instance = event.pool[attempt];
            configured = configure(instance) && restart(instance, event.spec) &&
                renderUpdates(64, abort);
        }
    } else {
        FMOD_STUDIO_EVENTINSTANCE *instance = event.persistent;
        configured = configure(instance) && restart(instance, event.spec);
        if (configured && warmPersistent) {
            configured = renderUpdates(event.spec->kind == EventKind::Engine ? 256 : 12, abort);
            meterStats_.reset();
        }
        if (configured) configured = renderUpdates(64, abort);
    }
    result.starts = event.counters.starts.load(std::memory_order_relaxed);
    result.sounds = event.counters.soundPlayed.load(std::memory_order_relaxed);
    result.frames = meterStats_.frames.load(std::memory_order_relaxed);
    result.samples = meterStats_.samples.load(std::memory_order_relaxed);
    result.sumSquaresQ = meterStats_.sumSquaresQ.load(std::memory_order_relaxed);
    result.nonFinite = meterStats_.nonFinite.load(std::memory_order_relaxed);
    std::uint32_t peakBits = meterStats_.peakBits.load(std::memory_order_relaxed);
    std::memcpy(&result.peak, &peakBits, sizeof(result.peak));
    const int names = std::min(event.counters.capturedNames.load(std::memory_order_relaxed),
                               static_cast<int>(EventCounters::kMaximumNames));
    for (int index = 0; index < names; ++index) {
        if (event.counters.soundNames[index][0] == '\0') continue;
        if (!result.soundNames.empty()) result.soundNames.push_back('|');
        result.soundNames.append(event.counters.soundNames[index].data());
    }
    const float requiredPeak = std::max(kSilenceThreshold, baselinePeak * 4.0f + kSilenceThreshold);
    const bool structurallyValid = configured && result.starts > 0 && result.sounds > 0 &&
        result.frames > 0 && result.samples > 0 && result.nonFinite == 0 &&
        std::isfinite(result.peak);
    result.passed = expectAudible
        ? structurallyValid && result.peak > requiredPeak &&
            meterStats_.audibleCallbacks.load(std::memory_order_relaxed) > 0
        : structurallyValid && result.peak <= requiredPeak &&
            meterStats_.audibleCallbacks.load(std::memory_order_relaxed) == 0;
    return result;
}

CheckResult FmodRuntime::captureTurboBov(
    RuntimeEvent &turbo, const std::atomic<bool> *abort, float baselinePeak) {
    CheckResult result{};
    result.kind = EventKind::Turbo;
    result.path = turbo.spec->path;
    result.detail = "warm boost then BOV rising edge";
    if (!muteAndStopAll() || !renderUpdates(3, abort)) return result;
    turbo.counters.reset();
    meterStats_.reset();
    bool configured = check(api_.EventInstance_SetVolume(turbo.persistent, 1.0f),
                            "unmute BOV validation") &&
        setParameter(turbo.persistent, "boost", profile_->validationBoost, turbo.spec) &&
        setParameter(turbo.persistent, "bov", 0.0f, turbo.spec) &&
        setParameter(turbo.persistent, "bov_decay", 10.0f, turbo.spec) &&
        restart(turbo.persistent, turbo.spec) && renderUpdates(128, abort);
    const int starts = turbo.counters.starts.load(std::memory_order_relaxed);
    // Isolate callback and PCM evidence to the actual 0 -> 1 BOV edge. Steady spool
    // callbacks from the warm-up cannot make this scenario pass.
    turbo.counters.soundPlayed.store(0, std::memory_order_relaxed);
    turbo.counters.capturedNames.store(0, std::memory_order_relaxed);
    for (auto &name : turbo.counters.soundNames) name[0] = '\0';
    meterStats_.reset();
    if (configured) {
        configured = setParameter(turbo.persistent, "bov", 1.0f, turbo.spec) &&
            setParameter(turbo.persistent, "bov_decay", 0.0f, turbo.spec);
    }
    // The Supra bank places its BOV instruments in descending boost regions (the high
    // sample is entered near 0.87, low near 0.72, and flutter near 0.60). A pedal lift
    // therefore needs both the BOV edge and the authored boost fall, just like live control.
    for (int step = 0; configured && step < 96; ++step) {
        const float fraction = static_cast<float>(step + 1) / 96.0f;
        configured = setParameter(
                         turbo.persistent, "boost",
                         profile_->validationBoost * (1.0f - 0.75f * fraction), turbo.spec) &&
            renderUpdates(1, abort);
    }
    if (configured) configured = setParameter(turbo.persistent, "bov", 0.0f, turbo.spec);
    for (int step = 0; configured && step < 128; ++step) {
        configured = setParameter(
                         turbo.persistent, "bov_decay",
                         std::min(10.0f, static_cast<float>(step + 1) * 0.01f), turbo.spec) &&
            renderUpdates(1, abort);
    }
    result.starts = starts;
    result.sounds = turbo.counters.soundPlayed.load(std::memory_order_relaxed);
    result.frames = meterStats_.frames.load(std::memory_order_relaxed);
    result.samples = meterStats_.samples.load(std::memory_order_relaxed);
    result.sumSquaresQ = meterStats_.sumSquaresQ.load(std::memory_order_relaxed);
    result.nonFinite = meterStats_.nonFinite.load(std::memory_order_relaxed);
    std::uint32_t peakBits = meterStats_.peakBits.load(std::memory_order_relaxed);
    std::memcpy(&result.peak, &peakBits, sizeof(result.peak));
    const int names = std::min(turbo.counters.capturedNames.load(std::memory_order_relaxed),
                               static_cast<int>(EventCounters::kMaximumNames));
    for (int index = 0; index < names; ++index) {
        if (turbo.counters.soundNames[index][0] == '\0') continue;
        if (!result.soundNames.empty()) result.soundNames.push_back('|');
        result.soundNames.append(turbo.counters.soundNames[index].data());
    }
    const float requiredPeak = std::max(kSilenceThreshold, baselinePeak * 4.0f + kSilenceThreshold);
    result.passed = configured && result.starts > 0 && result.sounds > 0 &&
        result.frames > 0 && result.samples > 0 && result.nonFinite == 0 &&
        std::isfinite(result.peak) && result.peak > requiredPeak &&
        meterStats_.audibleCallbacks.load(std::memory_order_relaxed) > 0;
    if (profile_ == &kProfiles[4]) {
        requireSoundNameEvidence(result, "Supra blow-off/flutter", {"bov", "flutter"});
    }
    return result;
}

CheckResult FmodRuntime::captureEngineTransition(
    RuntimeEvent &engine, EngineTransition transition, const std::atomic<bool> *abort,
    float baselinePeak) {
    CheckResult result{};
    result.kind = EventKind::Engine;
    result.path = engine.spec->path;
    switch (transition) {
        case EngineTransition::LimiterPulse:
            result.detail = "embedded high-RPM limiter crossing/pulse";
            break;
        case EngineTransition::ShiftDrop:
            result.detail = "embedded abrupt loaded-RPM shift drop";
            break;
        case EngineTransition::LiftBackfire:
            result.detail = "embedded armed-throttle lift/backfire";
            break;
    }
    if (!muteAndStopAll() || !renderUpdates(3, abort)) return result;
    engine.counters.reset();
    meterStats_.reset();
    const float startingRpm = transition == EngineTransition::LimiterPulse
        ? profile_->highRpm * 0.94f : profile_->highRpm * 0.84f;
    bool configured = check(api_.EventInstance_SetVolume(engine.persistent, 1.0f),
                            "unmute embedded-transition validation") &&
        setParameter(engine.persistent, "rpms", startingRpm, engine.spec) &&
        setParameter(engine.persistent, "throttle", 1.0f, engine.spec) &&
        restart(engine.persistent, engine.spec) && renderUpdates(128, abort);
    int starts = engine.counters.starts.load(std::memory_order_relaxed);
    std::uint64_t warmSamples = meterStats_.samples.load(std::memory_order_relaxed);
    std::uint64_t warmSumSquaresQ =
        meterStats_.sumSquaresQ.load(std::memory_order_relaxed);
    // Continuously scheduled layers (notably limiter and gear-change controllers) report
    // SOUND_PLAYED when engine_int starts, so their names are identity evidence only. The
    // lift transient does emit at its live pedal edge, so isolate its callback as well as PCM.
    if (transition == EngineTransition::LiftBackfire) engine.counters.reset();
    meterStats_.reset();
    if (configured) {
        switch (transition) {
            case EngineTransition::LimiterPulse:
                for (int step = 0; configured && step < 192; ++step) {
                    // Embedded limiter graphs receive no synthetic `decay` parameter. This is
                    // the same live engine_int trace: cross from below the limiter region and
                    // then remain latched at the profile maximum under full pedal.
                    const float fraction = std::min(
                        1.0f, static_cast<float>(step + 1) / 64.0f);
                    const float rpm = startingRpm +
                        fraction * (profile_->highRpm - startingRpm);
                    configured = setParameter(engine.persistent, "rpms", rpm, engine.spec) &&
                        setParameter(engine.persistent, "throttle", 1.0f, engine.spec) &&
                        renderUpdates(1, abort);
                }
                break;
            case EngineTransition::ShiftDrop:
                {
                // Aventador has no usable gear event. Reproduce its live 80 ms 1->2 cosmetic
                // shift: RPM holds until the 38% gear swap, then follows the new coupled RPM
                // through the same 24 ms exponential response. Pedal remains fully applied.
                constexpr int kLiveShiftControlSteps = 32;
                constexpr float kLiveAudioControlDt = 0.0025f;
                constexpr float kLiveSimulationDt = 0.005f;
                constexpr float kLiveUpshiftDuration = 0.080f;
                constexpr float kLiveGearChangeFraction = 0.38f;
                constexpr float kLivePostShiftResponse = 0.024f;
                constexpr float kLiveAudioFollowerResponse = 0.0075f;
                constexpr float kAventadorFirstToSecondRpmRatio = 2.44f / 3.91f;
                const float targetRpm = startingRpm * kAventadorFirstToSecondRpmRatio;
                float requestedRpm = startingRpm;
                float liveRpm = startingRpm;
                for (int step = 0; configured && step < kLiveShiftControlSteps; ++step) {
                    if ((step & 1) != 0) {
                        const float simulationElapsed =
                            static_cast<float>((step + 1) / 2) * kLiveSimulationDt;
                        if (simulationElapsed / kLiveUpshiftDuration >=
                            kLiveGearChangeFraction) {
                            const float simulationAlpha = 1.0f -
                                std::exp(-kLiveSimulationDt / kLivePostShiftResponse);
                            requestedRpm += (targetRpm - requestedRpm) * simulationAlpha;
                        }
                    }
                    const float presentationAlpha = 1.0f -
                        std::exp(-kLiveAudioControlDt / kLiveAudioFollowerResponse);
                    liveRpm += (requestedRpm - liveRpm) * presentationAlpha;
                    configured = setParameter(engine.persistent, "rpms", liveRpm, engine.spec) &&
                        setParameter(engine.persistent, "throttle", 1.0f, engine.spec) &&
                        renderUpdates(1, abort);
                }
                if (configured) configured = renderUpdates(128, abort);
                }
                break;
            case EngineTransition::LiftBackfire:
                configured = setParameter(engine.persistent, "rpms", startingRpm, engine.spec) &&
                    setParameter(engine.persistent, "throttle", 0.0f, engine.spec) &&
                    renderUpdates(160, abort);
            break;
        }
    }
    int transitionAttempts = 1;
    const auto capturedNameContains = [&](std::initializer_list<std::string_view> alternatives) {
        const int count = std::min(
            engine.counters.capturedNames.load(std::memory_order_relaxed),
            static_cast<int>(EventCounters::kMaximumNames));
        for (int index = 0; index < count; ++index) {
            const std::string_view name(engine.counters.soundNames[index].data());
            if (std::any_of(alternatives.begin(), alternatives.end(),
                            [&](std::string_view token) {
                                return containsAsciiIgnoreCase(name, token);
                            })) return true;
        }
        return false;
    };
    // Aventador's embedded lift instruments retain their authored ~50% trigger chances.
    // Retry fresh deterministic lift captures just as pooled stochastic one-shots are probed;
    // the live runtime remains single-trigger and preserves the authored randomness.
    while (configured && profile_ == &kProfiles[2] &&
           transition == EngineTransition::LiftBackfire && transitionAttempts < 8 &&
           !capturedNameContains({"throttlefart", "gintanisvjboom"})) {
        engine.counters.reset();
        meterStats_.reset();
        configured = check(api_.EventInstance_SetVolume(engine.persistent, 1.0f),
                           "unmute embedded lift retry") &&
            setParameter(engine.persistent, "rpms", startingRpm, engine.spec) &&
            setParameter(engine.persistent, "throttle", 1.0f, engine.spec) &&
            restart(engine.persistent, engine.spec) && renderUpdates(128, abort);
        starts += engine.counters.starts.load(std::memory_order_relaxed);
        warmSamples = meterStats_.samples.load(std::memory_order_relaxed);
        warmSumSquaresQ = meterStats_.sumSquaresQ.load(std::memory_order_relaxed);
        engine.counters.reset();
        meterStats_.reset();
        if (configured) {
            configured = setParameter(engine.persistent, "rpms", startingRpm, engine.spec) &&
                setParameter(engine.persistent, "throttle", 0.0f, engine.spec) &&
                renderUpdates(160, abort);
        }
        ++transitionAttempts;
    }
    result.starts = starts;
    result.sounds = engine.counters.soundPlayed.load(std::memory_order_relaxed);
    result.frames = meterStats_.frames.load(std::memory_order_relaxed);
    result.samples = meterStats_.samples.load(std::memory_order_relaxed);
    result.sumSquaresQ = meterStats_.sumSquaresQ.load(std::memory_order_relaxed);
    result.nonFinite = meterStats_.nonFinite.load(std::memory_order_relaxed);
    std::uint32_t peakBits = meterStats_.peakBits.load(std::memory_order_relaxed);
    std::memcpy(&result.peak, &peakBits, sizeof(result.peak));
    const int names = std::min(engine.counters.capturedNames.load(std::memory_order_relaxed),
                               static_cast<int>(EventCounters::kMaximumNames));
    for (int index = 0; index < names; ++index) {
        if (engine.counters.soundNames[index][0] == '\0') continue;
        if (!result.soundNames.empty()) result.soundNames.push_back('|');
        result.soundNames.append(engine.counters.soundNames[index].data());
    }
    const float requiredPeak = std::max(kSilenceThreshold, baselinePeak * 4.0f + kSilenceThreshold);
    result.passed = configured && result.starts > 0 && result.sounds > 0 &&
        result.frames > 0 && result.samples > 0 && result.nonFinite == 0 &&
        std::isfinite(result.peak) && result.peak > requiredPeak &&
        meterStats_.audibleCallbacks.load(std::memory_order_relaxed) > 0;
    switch (transition) {
        case EngineTransition::LimiterPulse:
            result.detail.append("; callback scope=event-start identity (not firing proof)");
            if (profile_ == &kProfiles[1]) {
                requireSoundNameEvidence(result, "Hur_LIM", {"hur_lim"});
            } else if (profile_ == &kProfiles[2]) {
                requireSoundNameEvidence(
                    result, "Aventador authored high/limiter region",
                    {"aventadorintacc8294", "powercraftaventadorextaccveryhigh",
                     "gintanisvjextaccsavagehigh"});
            } else if (profile_ == &kProfiles[4]) {
                requireSoundNameEvidence(result, "Supra limiter", {"limiter"});
            }
            break;
        case EngineTransition::ShiftDrop:
            result.detail.append("; callback scope=event-start identity (not firing proof)");
            if (profile_ == &kProfiles[2]) {
                requireSoundNameEvidence(
                    result, "GEAR_CHANGING_CABIN", {"gear_changing_cabin"});
            }
            break;
        case EngineTransition::LiftBackfire:
            result.detail.append("; callback scope=isolated live throttle edge");
            if (profile_ == &kProfiles[2]) {
                requireSoundNameEvidence(
                    result, "Aventador throttle-fart/boom",
                    {"throttlefart", "gintanisvjboom"});
            }
            break;
    }
    const double warmRms = warmSamples == 0 ? 0.0 :
        std::sqrt(static_cast<double>(warmSumSquaresQ) / 1.0e9 /
                  static_cast<double>(warmSamples));
    const double transitionRms = result.samples == 0 ? 0.0 :
        std::sqrt(static_cast<double>(result.sumSquaresQ) / 1.0e9 /
                  static_cast<double>(result.samples));
    const double rmsDeltaDb = warmRms > 0.0 && transitionRms > 0.0
        ? 20.0 * std::log10(transitionRms / warmRms) : 0.0;
    const double minimumAbsoluteDeltaDb = transition == EngineTransition::LiftBackfire
        ? 1.0 : (transition == EngineTransition::LimiterPulse ? 0.15 : 0.25);
    const bool targetPcmEvidence = warmRms > 0.0 && transitionRms > 0.0 &&
        std::isfinite(rmsDeltaDb) && std::fabs(rmsDeltaDb) >= minimumAbsoluteDeltaDb;
    result.passed = result.passed && targetPcmEvidence;
    const char *traceContract = "";
    switch (transition) {
        case EngineTransition::LimiterPulse:
            traceContract = "below-limit>ramp-to-limit>latched;throttle=1";
            break;
        case EngineTransition::ShiftDrop:
            traceContract =
                "400Hz/80ms-upshift;gear-swap=38%;simulation-tau=24ms;"
                "presentation-tau=7.5ms;throttle=1";
            break;
        case EngineTransition::LiftBackfire:
            traceContract = "rpm-held;throttle=1>0-edge";
            break;
    }
    std::ostringstream transitionDetail;
    transitionDetail << "; exactTrace=" << traceContract
                     << "; targetPcmEvidence=" << (targetPcmEvidence ? "true" : "false")
                     << "; isolated transition PCM"
                     << "[warmRms=" << warmRms << ",transitionRms=" << transitionRms
                     << ",deltaDb=" << rmsDeltaDb
                     << ",minimumAbsDeltaDb=" << minimumAbsoluteDeltaDb
                     << ",attempts=" << transitionAttempts << ']';
    result.detail.append(transitionDetail.str());
    return result;
}

CheckResult FmodRuntime::captureEngineIgnition(
    RuntimeEvent &engine, const std::atomic<bool> *abort, float baselinePeak) {
    CheckResult result{};
    result.kind = EventKind::Engine;
    result.path = engine.spec->path;
    result.detail = "ignition 0-to-idle RPM ramp";
    if (!muteAndStopAll() || !renderUpdates(3, abort)) return result;
    engine.counters.reset();
    meterStats_.reset();
    bool configured = check(api_.EventInstance_SetVolume(engine.persistent, 1.0f),
                            "unmute ignition validation") &&
        setParameter(engine.persistent, "rpms", 1.0f, engine.spec) &&
        setParameter(engine.persistent, "throttle", 0.0f, engine.spec) &&
        restart(engine.persistent, engine.spec);
    for (int step = 0; configured && step < 160; ++step) {
        const float fraction = std::min(1.0f, static_cast<float>(step) / 80.0f);
        configured = setParameter(engine.persistent, "rpms",
                                  1.0f + fraction * (profile_->idleRpm - 1.0f), engine.spec) &&
            renderUpdates(1, abort);
    }
    result.starts = engine.counters.starts.load(std::memory_order_relaxed);
    result.sounds = engine.counters.soundPlayed.load(std::memory_order_relaxed);
    result.frames = meterStats_.frames.load(std::memory_order_relaxed);
    result.samples = meterStats_.samples.load(std::memory_order_relaxed);
    result.sumSquaresQ = meterStats_.sumSquaresQ.load(std::memory_order_relaxed);
    result.nonFinite = meterStats_.nonFinite.load(std::memory_order_relaxed);
    std::uint32_t peakBits = meterStats_.peakBits.load(std::memory_order_relaxed);
    std::memcpy(&result.peak, &peakBits, sizeof(result.peak));
    const int names = std::min(engine.counters.capturedNames.load(std::memory_order_relaxed),
                               static_cast<int>(EventCounters::kMaximumNames));
    for (int index = 0; index < names; ++index) {
        if (engine.counters.soundNames[index][0] == '\0') continue;
        if (!result.soundNames.empty()) result.soundNames.push_back('|');
        result.soundNames.append(engine.counters.soundNames[index].data());
    }
    const float requiredPeak = std::max(kSilenceThreshold, baselinePeak * 4.0f + kSilenceThreshold);
    result.passed = configured && result.starts > 0 && result.sounds > 0 &&
        result.frames > 0 && result.samples > 0 && result.nonFinite == 0 &&
        result.peak > requiredPeak;
    if (profile_ == &kProfiles[4]) {
        requireSoundNameEvidence(result, "Supra ignition", {"ignition"});
    }
    return result;
}

CheckResult FmodRuntime::captureEngineShutdown(
    RuntimeEvent &engine, const std::atomic<bool> *abort, float baselinePeak) {
    CheckResult result{};
    result.kind = EventKind::Engine;
    result.path = engine.spec->path;
    result.detail = "RPM/fade shutdown lifecycle";
    if (!muteAndStopAll() || !renderUpdates(3, abort)) return result;
    engine.counters.reset();
    meterStats_.reset();
    bool configured = check(api_.EventInstance_SetVolume(engine.persistent, 1.0f),
                            "unmute shutdown validation") &&
        setParameter(engine.persistent, "rpms", profile_->highRpm * 0.55f, engine.spec) &&
        setParameter(engine.persistent, "throttle", 0.0f, engine.spec) &&
        restart(engine.persistent, engine.spec) && renderUpdates(96, abort);
    meterStats_.reset();
    for (int step = 0; configured && step < 96; ++step) {
        const float fraction = static_cast<float>(step) / 95.0f;
        configured = setParameter(engine.persistent, "rpms",
                                  (1.0f - fraction) * profile_->highRpm * 0.55f + 1.0f,
                                  engine.spec) &&
            check(api_.EventInstance_SetVolume(engine.persistent, 1.0f - fraction),
                  "apply shutdown validation fade") && renderUpdates(1, abort);
    }
    if (configured) {
        configured = check(api_.EventInstance_Stop(
                               engine.persistent, FMOD_STUDIO_STOP_ALLOWFADEOUT),
                           "finish shutdown validation") && renderUpdates(64, abort);
    }
    result.starts = engine.counters.starts.load(std::memory_order_relaxed);
    result.sounds = engine.counters.soundPlayed.load(std::memory_order_relaxed);
    result.frames = meterStats_.frames.load(std::memory_order_relaxed);
    result.samples = meterStats_.samples.load(std::memory_order_relaxed);
    result.sumSquaresQ = meterStats_.sumSquaresQ.load(std::memory_order_relaxed);
    result.nonFinite = meterStats_.nonFinite.load(std::memory_order_relaxed);
    std::uint32_t peakBits = meterStats_.peakBits.load(std::memory_order_relaxed);
    std::memcpy(&result.peak, &peakBits, sizeof(result.peak));
    const int names = std::min(engine.counters.capturedNames.load(std::memory_order_relaxed),
                               static_cast<int>(EventCounters::kMaximumNames));
    for (int index = 0; index < names; ++index) {
        if (engine.counters.soundNames[index][0] == '\0') continue;
        if (!result.soundNames.empty()) result.soundNames.push_back('|');
        result.soundNames.append(engine.counters.soundNames[index].data());
    }
    const float requiredPeak = std::max(kSilenceThreshold, baselinePeak * 4.0f + kSilenceThreshold);
    result.passed = configured && result.starts > 0 && result.sounds > 0 &&
        result.frames > 0 && result.samples > 0 && result.nonFinite == 0 &&
        result.peak > requiredPeak;
    if (profile_ == &kProfiles[4]) {
        requireSoundNameEvidence(result, "Supra shutdown", {"shutdown"});
    }
    return result;
}

CheckResult FmodRuntime::combineChecks(
    const CheckResult &left, const CheckResult &right, const char *detail) {
    CheckResult result = left;
    result.detail.assign(detail);
    result.detail.append("; previous[");
    result.detail.append(left.detail);
    result.detail.append(",passed=");
    result.detail.append(left.passed ? "true" : "false");
    result.detail.append("]");
    result.detail.append("; latest[");
    result.detail.append(right.detail);
    result.detail.append(",passed=");
    result.detail.append(right.passed ? "true" : "false");
    result.detail.push_back(']');
    result.starts += right.starts;
    result.sounds += right.sounds;
    result.frames += right.frames;
    result.samples += right.samples;
    result.sumSquaresQ += right.sumSquaresQ;
    result.nonFinite += right.nonFinite;
    result.peak = std::max(left.peak, right.peak);
    result.passed = left.passed && right.passed;
    if (!right.soundNames.empty()) {
        if (!result.soundNames.empty()) result.soundNames.push_back('|');
        result.soundNames.append(right.soundNames);
    }
    return result;
}

std::string FmodRuntime::validateRenderedAudio() {
    const ProfileSpec *profile = nullptr;
    {
        std::lock_guard<std::mutex> guard(mutex_);
        if (!loaded_ || profile_ == nullptr) {
            return validationFailureJson("FMOD banks are not loaded.", nullptr, 0);
        }
        profile = profile_;
        validationAbort_.store(false, std::memory_order_relaxed);
    }
    const auto started = std::chrono::steady_clock::now();
    FmodRuntime validator(OutputMode::NoSoundNrt);
    if (!validator.initialize() || !validator.loadBanks(profile->id)) {
        return validationFailureJson(validator.lastError(), profile, elapsedMilliseconds(started));
    }
    const std::string result = validator.runValidation(&validationAbort_, started);
    validator.close();
    return result;
}

std::string FmodRuntime::runValidation(
    const std::atomic<bool> *abort, std::chrono::steady_clock::time_point started) {
    std::array<CheckResult, 6> results{};
    std::size_t count = 0;
    if (!muteAndStopAll()) return validationFailureJson(lastError_, profile_, elapsedMilliseconds(started));
    meterStats_.reset();
    if (!renderUpdates(12, abort)) {
        const char *error = abort->load(std::memory_order_relaxed)
            ? "FMOD rendered-audio validation was cancelled." : lastError_.c_str();
        return validationFailureJson(error, profile_, elapsedMilliseconds(started));
    }
    std::uint32_t baselineBits = meterStats_.peakBits.load(std::memory_order_relaxed);
    float baselinePeak = 0.0f;
    std::memcpy(&baselinePeak, &baselineBits, sizeof(baselinePeak));

    RuntimeEvent &engine = eventFor(EventKind::Engine);
    CheckResult engineResult = captureEngineIgnition(engine, abort, baselinePeak);
    engineResult = combineChecks(engineResult,
        captureScenario(engine, "idle/coast", profile_->idleRpm, 0.0f, abort, baselinePeak),
        "ignition + idle/coast");
    engineResult = combineChecks(engineResult,
        captureScenario(engine, "mid-rpm coast", profile_->highRpm * 0.55f, 0.0f,
                        abort, baselinePeak),
        "ignition + idle + mid coast");
    engineResult = combineChecks(engineResult,
        captureScenario(engine, "mid-rpm load", profile_->highRpm * 0.55f, 1.0f,
                        abort, baselinePeak),
        "ignition + idle/coast/load");
    engineResult = combineChecks(engineResult,
        captureScenario(engine, "high/limiter engine region", profile_->highRpm, 1.0f,
                        abort, baselinePeak),
        "ignition + idle/coast/load/high-limiter");
    engineResult = combineChecks(engineResult,
        captureEngineShutdown(engine, abort, baselinePeak),
        "ignition, RPM/load/coast/high regions, shutdown fade");
    if ((profile_->embeddedValidation & kEmbeddedLimiter) != 0) {
        engineResult = combineChecks(
            engineResult,
            captureEngineTransition(
                engine, EngineTransition::LimiterPulse, abort, baselinePeak),
            "ignition/RPM/load/coast/shutdown + embedded limiter pulse");
    }
    if ((profile_->embeddedValidation & kEmbeddedShift) != 0) {
        engineResult = combineChecks(
            engineResult,
            captureEngineTransition(engine, EngineTransition::ShiftDrop, abort, baselinePeak),
            "ignition/RPM/load/coast/shutdown + embedded limiter + shift drop");
    }
    if ((profile_->embeddedValidation & kEmbeddedBackfire) != 0) {
        engineResult = combineChecks(
            engineResult,
            captureEngineTransition(
                engine, EngineTransition::LiftBackfire, abort, baselinePeak),
            "ignition/RPM/load/coast/shutdown + embedded limiter/shift/lift-backfire");
    }
    const auto rmsLinear = [](const CheckResult &check) {
        if (check.samples == 0) return 0.0;
        return std::sqrt(static_cast<double>(check.sumSquaresQ) / 1.0e9 /
                         static_cast<double>(check.samples));
    };
    const float gainProbeRpm = profile_->idleRpm +
        (profile_->highRpm - profile_->idleRpm) * 0.45f;
    const CheckResult engineFull = captureScenario(
        engine, "engine unity-gain probe", gainProbeRpm, 0.7f,
        abort, baselinePeak, true, 1.0f, true);
    const CheckResult engineQuarter = captureScenario(
        engine, "engine quarter-gain probe", gainProbeRpm, 0.7f,
        abort, baselinePeak, true, 0.25f, true);
    const CheckResult engineMuted = captureScenario(
        engine, "engine disabled/muted probe", gainProbeRpm, 0.7f,
        abort, baselinePeak, true, 0.0f, false);
    const double engineFullRms = rmsLinear(engineFull);
    const double engineQuarterRms = rmsLinear(engineQuarter);
    const double engineGainRatio = engineFullRms > 0.0 ? engineQuarterRms / engineFullRms : 0.0;
    const bool engineGainMutePassed = engineFull.passed && engineQuarter.passed &&
        engineMuted.passed && engineGainRatio >= 0.12 && engineGainRatio <= 0.45;
    engineResult.passed = engineResult.passed && engineGainMutePassed;
    {
        std::ostringstream gainDetail;
        gainDetail << "; rendered event gain/mute probe[passed="
                   << (engineGainMutePassed ? "true" : "false")
                   << ",quarterRmsRatio=" << engineGainRatio
                   << ",mutedPeak=" << engineMuted.peak << ']';
        engineResult.detail.append(gainDetail.str());
    }
    results[count++] = std::move(engineResult);

    RuntimeEvent &turbo = eventFor(EventKind::Turbo);
    if (turbo.spec != nullptr) {
        CheckResult turboResult = captureScenario(
            turbo, "boost", profile_->validationBoost, 0.0f, abort, baselinePeak);
        if (profile_ == &kProfiles[4]) {
            turboResult = combineChecks(
                turboResult, captureTurboBov(turbo, abort, baselinePeak),
                "boost + isolated BOV edge");
        } else {
            turboResult.detail.append(
                "; BOV controls are not claimed audible for this profile");
        }
        results[count++] = std::move(turboResult);
    }
    RuntimeEvent &limiter = eventFor(EventKind::Limiter);
    if (limiter.spec != nullptr) {
        results[count++] = captureScenario(
            limiter, "limiter decay pulse", 0.0f, 0.0f, abort, baselinePeak);
    }
    RuntimeEvent &shifts = eventFor(EventKind::Shifts);
    if (shifts.spec != nullptr) {
        CheckResult shiftResult = captureScenario(
            shifts, "upshift", 1.0f, 0.0f, abort, baselinePeak);
        shiftResult = combineChecks(shiftResult,
            captureScenario(shifts, "downshift", 0.0f, 0.0f, abort, baselinePeak),
            "upshift + downshift");
        if (profile_ == &kProfiles[0]) {
            const CheckResult shiftFull = captureScenario(
                shifts, "one-shot unity-gain probe", 1.0f, 0.0f,
                abort, baselinePeak, false, 1.0f, true);
            const CheckResult shiftQuarter = captureScenario(
                shifts, "one-shot quarter-gain probe", 1.0f, 0.0f,
                abort, baselinePeak, false, 0.25f, true);
            const CheckResult shiftMuted = captureScenario(
                shifts, "one-shot disabled/muted probe", 1.0f, 0.0f,
                abort, baselinePeak, false, 0.0f, false);
            const double fullRms = rmsLinear(shiftFull);
            const double quarterRms = rmsLinear(shiftQuarter);
            const double ratio = fullRms > 0.0 ? quarterRms / fullRms : 0.0;
            const bool gainMutePassed = shiftFull.passed && shiftQuarter.passed &&
                shiftMuted.passed && ratio >= 0.05 && ratio <= 0.75;
            shiftResult.passed = shiftResult.passed && gainMutePassed;
            std::ostringstream gainDetail;
            gainDetail << "; rendered pooled one-shot gain/mute probe[passed="
                       << (gainMutePassed ? "true" : "false")
                       << ",quarterRmsRatio=" << ratio
                       << ",mutedPeak=" << shiftMuted.peak << ']';
            shiftResult.detail.append(gainDetail.str());
        }
        results[count++] = std::move(shiftResult);
    }
    RuntimeEvent &backfire = eventFor(EventKind::Backfire);
    if (backfire.spec != nullptr) {
        results[count++] = captureScenario(
            backfire, "lift/backfire", 0.0f, 0.0f, abort, baselinePeak);
    }
    RuntimeEvent &transmission = eventFor(EventKind::Transmission);
    if (transmission.spec != nullptr) {
        const float speed = std::max(80.0f, profile_->maximumDrivetrainSpeed * 0.55f);
        CheckResult loadResult = captureScenario(
            transmission, "drivetrain load", speed, 1.0f, abort, baselinePeak);
        CheckResult coastResult = captureScenario(
            transmission, "drivetrain coast", speed, 0.0f, abort, baselinePeak);
        CheckResult transmissionResult = combineChecks(
            loadResult, coastResult, "drivetrain load + coast");
        const auto healthyRender = [](const CheckResult &check) {
            return check.frames > 0 && check.samples > 0 && check.nonFinite == 0 &&
                std::isfinite(check.peak);
        };
        // A transmission graph may intentionally mute its load branch (the Aventador authors
        // attenuate throttle=1 to -42 dB). The capability is functional when both branches
        // render safely and at least one authored branch proves audible PCM/callbacks.
        transmissionResult.passed = healthyRender(loadResult) && healthyRender(coastResult) &&
            (loadResult.passed || coastResult.passed);
        std::ostringstream branchDetail;
        branchDetail << "drivetrain load[passed=" << (loadResult.passed ? "true" : "false")
                     << ",sounds=" << loadResult.sounds << ",peak=" << loadResult.peak
                     << "]; coast[passed=" << (coastResult.passed ? "true" : "false")
                     << ",sounds=" << coastResult.sounds << ",peak=" << coastResult.peak << ']';
        transmissionResult.detail = branchDetail.str();
        results[count++] = std::move(transmissionResult);
    }
    if (abort->load(std::memory_order_relaxed)) {
        return validationFailureJson(
            "FMOD rendered-audio validation was cancelled.", profile_, elapsedMilliseconds(started));
    }
    bool passed = excludedInstantiationCount_ == 0 && count > 0;
    for (std::size_t index = 0; index < count; ++index) passed = passed && results[index].passed;
    std::ostringstream json;
    json << "{\"schema\":\"fmod-render-validation-v1\",\"profileId\":\""
         << profile_->id << "\",\"passed\":" << (passed ? "true" : "false")
         << ",\"output\":\"NOSOUND_NRT+STREAM_FROM_UPDATE/512x4/synchronous\""
         << ",\"sampleRate\":48000"
         << ",\"channels\":2,\"excludedInstantiationCount\":"
         << excludedInstantiationCount_ << ",\"durationMilliseconds\":"
         << elapsedMilliseconds(started) << ",\"baselinePeak\":" << baselinePeak
         << ",\"checks\":[";
    for (std::size_t index = 0; index < count; ++index) {
        if (index != 0) json << ',';
        appendCheckJson(json, results[index]);
    }
    json << "]}";
    return json.str();
}

void FmodRuntime::appendCheckJson(std::ostringstream &json, const CheckResult &result) {
    const double peakDb = result.peak > 0.0f
        ? 20.0 * std::log10(static_cast<double>(result.peak)) : -120.0;
    const double meanSquare = result.samples > 0
        ? static_cast<double>(result.sumSquaresQ) / 1.0e9 / static_cast<double>(result.samples)
        : 0.0;
    const double rmsDb = meanSquare > 0.0 ? 20.0 * std::log10(std::sqrt(meanSquare)) : -120.0;
    json << "{\"kind\":\"" << kindName(result.kind) << "\",\"eventPath\":\""
         << result.path << "\",\"instanceStarts\":" << result.starts
         << ",\"soundPlayedCallbacks\":" << result.sounds
         << ",\"renderedFrames\":" << result.frames
         << ",\"peakDbfs\":" << peakDb << ",\"rmsDbfs\":" << rmsDb
         << ",\"nonFiniteSamples\":" << result.nonFinite
         << ",\"passed\":" << (result.passed ? "true" : "false")
         << ",\"detail\":\"" << jsonEscape(result.detail)
         << "\",\"soundNames\":\"" << jsonEscape(result.soundNames) << "\"}";
}

std::uint64_t FmodRuntime::elapsedMilliseconds(std::chrono::steady_clock::time_point started) {
    return static_cast<std::uint64_t>(std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - started).count());
}

std::string FmodRuntime::jsonEscape(std::string_view value) {
    std::string escaped;
    escaped.reserve(value.size());
    for (const char character : value) {
        if (character == '\\' || character == '"') escaped.push_back('\\');
        if (character == '\n' || character == '\r' || character == '\t') escaped.push_back(' ');
        else escaped.push_back(character);
    }
    return escaped;
}

std::string FmodRuntime::validationFailureJson(
    std::string_view error, const ProfileSpec *profile, std::uint64_t durationMs) {
    std::ostringstream json;
    json << "{\"schema\":\"fmod-render-validation-v1\",\"profileId\":\""
         << (profile == nullptr ? "" : profile->id)
         << "\",\"passed\":false,\"excludedInstantiationCount\":0"
         << ",\"durationMilliseconds\":" << durationMs << ",\"checks\":[]"
         << ",\"error\":\"" << jsonEscape(error) << "\"}";
    return json.str();
}

bool FmodRuntime::suspendMixer() {
    std::lock_guard<std::mutex> guard(mutex_);
    if (!initialized_ || lowLevelSystem_ == nullptr) return fail("FMOD is not initialized.");
    if (suspended_) return true;
    if (!check(api_.System_MixerSuspend(lowLevelSystem_), "suspend FMOD mixer")) return false;
    suspended_ = true;
    return true;
}

bool FmodRuntime::resumeMixer() {
    std::lock_guard<std::mutex> guard(mutex_);
    if (!initialized_ || lowLevelSystem_ == nullptr) return fail("FMOD is not initialized.");
    if (!suspended_) return true;
    if (!check(api_.System_MixerResume(lowLevelSystem_), "resume FMOD mixer")) return false;
    suspended_ = false;
    return true;
}

std::string FmodRuntime::diagnostics() const {
    std::lock_guard<std::mutex> guard(mutex_);
    std::ostringstream mixerDuration;
    mixerDuration << std::fixed << std::setprecision(3)
                  << static_cast<double>(actualDspBufferFrames_) * 1000.0 / 48000.0;
    std::ostringstream result;
    result << "FMOD 1.10.11; output="
           << (outputMode_ == OutputMode::NoSoundNrt ? "NOSOUND_NRT" : "device")
           << "/48000Hz stereo; dsp="
           << actualDspBufferFrames_ << 'x' << actualDspBufferCount_
           << "; studioUpdate="
           << "synchronous-400Hz/" << mixerDuration.str() << "ms-mixer"
           << "; initialized=" << (initialized_ ? "true" : "false")
           << "; loaded=" << (loaded_ ? "true" : "false")
           << "; suspended=" << (suspended_ ? "true" : "false")
           << "; plugins=FMOD Distance Filter,FMOD Gain,FMOD Distortion"
           << "; compatibility=distance-pass-nearfield+gain-db-ramp+distortion-hard-clip"
           << "; pluginSdk=" << FMOD_PLUGIN_SDK_VERSION << "(FMOD-1.10-host)"
           << "; gainSets=" << gGainFloatSetCount.load(std::memory_order_relaxed)
           << "/lastDb=" << gGainLastDb.load(std::memory_order_relaxed)
           << "/invertSets=" << gGainBoolSetCount.load(std::memory_order_relaxed)
           << "; distortionSets=" << gDistortionSetCount.load(std::memory_order_relaxed)
           << "/lastLevel=" << gDistortionLastLevel.load(std::memory_order_relaxed)
           << "; validation=bank-guid+exact-profile+allowlisted-guids+paths+parameters+NRT-PCM"
           << "; samples=preloaded; excludedInstantiations=" << excludedInstantiationCount_
           << "; limiterEdges=accepted:" << acceptedLimiterEdges_
           << "/delivered:" << deliveredLimiterPulses_
           << "/pending:" << pendingLimiterPulses_
           << "/phase:"
           << (limiterPulseZeroHoldUpdatesRemaining_ > 0
                   ? "zero-hold" : (limiterPulseNeedsRearm_ ? "rearm" : "ready"))
           << "/zeroHold:" << limiterPulseZeroHoldUpdatesRemaining_
           << "/cooldown:" << limiterPulseCooldownUpdates_;
    if (profile_ != nullptr) {
        result << "; profile=" << profile_->id
               << "; banks=common.strings.bank>common.bank>" << profile_->bankFileName
               << "; events=";
        bool first = true;
        for (const auto &event : events_) {
            if (event.spec == nullptr) continue;
            if (!first) result << ',';
            result << event.spec->path;
            first = false;
        }
    }
    if (!lastError_.empty()) result << "; lastError=" << lastError_;
    return result.str();
}

std::string FmodRuntime::lastError() const {
    std::lock_guard<std::mutex> guard(mutex_);
    return lastError_.empty() ? "Unknown FMOD runtime error." : lastError_;
}

void FmodRuntime::close() {
    std::lock_guard<std::mutex> guard(mutex_);
    closeUnlocked();
}

void FmodRuntime::releaseInstance(FMOD_STUDIO_EVENTINSTANCE *&instance) {
    if (instance == nullptr) return;
    api_.EventInstance_Stop(instance, FMOD_STUDIO_STOP_IMMEDIATE);
    if (outputMode_ == OutputMode::NoSoundNrt) {
        api_.EventInstance_SetCallback(instance, nullptr, 0);
        api_.EventInstance_SetUserData(instance, nullptr);
    }
    api_.EventInstance_Release(instance);
    instance = nullptr;
}

void FmodRuntime::unloadBanksUnlocked() {
    for (auto &event : events_) {
        releaseInstance(event.persistent);
        for (auto *&instance : event.pool) releaseInstance(instance);
        event.spec = nullptr;
        event.description = nullptr;
        event.poolCount = 0;
        event.cursor = 0;
        event.started = false;
        event.appliedVolume = std::numeric_limits<float>::quiet_NaN();
        event.counters.reset();
    }
    if (studioSystem_ != nullptr) api_.Studio_System_UnloadAll(studioSystem_);
    stringsBank_ = nullptr;
    commonBank_ = nullptr;
    carBank_ = nullptr;
    profile_ = nullptr;
    loaded_ = false;
    allowlistedInstanceCount_ = 0;
    excludedInstantiationCount_ = 0;
}

void FmodRuntime::closeUnlocked() {
    if (studioSystem_ == nullptr) {
        api_.close();
        return;
    }
    if (suspended_ && lowLevelSystem_ != nullptr) api_.System_MixerResume(lowLevelSystem_);
    suspended_ = false;
    unloadBanksUnlocked();
    if (meterAttached_ && masterGroup_ != nullptr && meterDsp_ != nullptr) {
        api_.ChannelGroup_RemoveDSP(masterGroup_, meterDsp_);
    }
    meterAttached_ = false;
    masterGroup_ = nullptr;
    if (meterDsp_ != nullptr) api_.DSP_Release(meterDsp_);
    meterDsp_ = nullptr;
    if (pluginsRegistered_) {
        api_.Studio_System_UnregisterPlugin(studioSystem_, "FMOD Distortion");
        api_.Studio_System_UnregisterPlugin(studioSystem_, "FMOD Gain");
        api_.Studio_System_UnregisterPlugin(studioSystem_, "FMOD Distance Filter");
    }
    pluginsRegistered_ = false;
    api_.Studio_System_Release(studioSystem_);
    studioSystem_ = nullptr;
    lowLevelSystem_ = nullptr;
    initialized_ = false;
    api_.close();
}

bool FmodRuntime::check(FMOD_RESULT result, std::string_view operation) {
    if (result == FMOD_OK) return true;
    std::string detail(operation);
    detail.append(": ").append(FMOD_ErrorString(result)).append(" (")
        .append(std::to_string(static_cast<int>(result))).append(")");
    return fail(std::move(detail));
}

bool FmodRuntime::checkEventOperation(
    FMOD_RESULT result, const char *operation, const char *eventPath) {
    if (result == FMOD_OK) return true;
    std::string detail(operation);
    detail.append(eventPath);
    return check(result, detail);
}

bool FmodRuntime::fail(std::string detail) {
    lastError_ = std::move(detail);
    logError(lastError_);
    return false;
}

std::mutex gRegistryMutex;
std::unordered_map<jlong, std::shared_ptr<FmodRuntime>> gRuntimes;
std::atomic<jlong> gNextHandle{1};
std::string gLastCreateError;

std::shared_ptr<FmodRuntime> runtimeFor(jlong handle) {
    std::lock_guard<std::mutex> guard(gRegistryMutex);
    const auto found = gRuntimes.find(handle);
    return found == gRuntimes.end() ? nullptr : found->second;
}

jstring javaString(JNIEnv *environment, const std::string &value) {
    return environment->NewStringUTF(value.c_str());
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_gabrielpc_enginesoundsimulator_audio_FmodNativeBindings_create(JNIEnv *, jobject) {
    auto runtime = std::make_shared<FmodRuntime>();
    if (!runtime->initialize()) {
        std::lock_guard<std::mutex> guard(gRegistryMutex);
        gLastCreateError = runtime->lastError();
        return 0;
    }
    const jlong handle = gNextHandle.fetch_add(1);
    {
        std::lock_guard<std::mutex> guard(gRegistryMutex);
        gRuntimes.emplace(handle, std::move(runtime));
        gLastCreateError.clear();
    }
    return handle;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_gabrielpc_enginesoundsimulator_audio_FmodNativeBindings_loadBanks(
    JNIEnv *environment, jobject, jlong handle, jstring profileId) {
    const auto runtime = runtimeFor(handle);
    if (runtime == nullptr || profileId == nullptr) return JNI_FALSE;
    const char *characters = environment->GetStringUTFChars(profileId, nullptr);
    if (characters == nullptr) return JNI_FALSE;
    const bool loaded = runtime->loadBanks(characters);
    environment->ReleaseStringUTFChars(profileId, characters);
    return loaded ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_gabrielpc_enginesoundsimulator_audio_FmodNativeBindings_update(
    JNIEnv *environment, jobject, jlong handle, jobject controlBuffer) {
    const auto runtime = runtimeFor(handle);
    if (runtime == nullptr) return JNI_FALSE;
    auto *bytes = static_cast<std::uint8_t *>(environment->GetDirectBufferAddress(controlBuffer));
    const jlong capacity = environment->GetDirectBufferCapacity(controlBuffer);
    return runtime->update(bytes, capacity > 0 ? static_cast<std::size_t>(capacity) : 0)
        ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_gabrielpc_enginesoundsimulator_audio_FmodNativeBindings_validateRenderedAudio(
    JNIEnv *environment, jobject, jlong handle) {
    const auto runtime = runtimeFor(handle);
    return javaString(environment, runtime == nullptr
        ? "{\"schema\":\"fmod-render-validation-v1\",\"passed\":false,\"error\":\"invalid runtime handle\",\"checks\":[]}"
        : runtime->validateRenderedAudio());
}

extern "C" JNIEXPORT void JNICALL
Java_com_gabrielpc_enginesoundsimulator_audio_FmodNativeBindings_cancelRenderedAudioValidation(
    JNIEnv *, jobject, jlong handle) {
    const auto runtime = runtimeFor(handle);
    if (runtime != nullptr) runtime->requestValidationAbort();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_gabrielpc_enginesoundsimulator_audio_FmodNativeBindings_suspendMixer(
    JNIEnv *, jobject, jlong handle) {
    const auto runtime = runtimeFor(handle);
    return runtime != nullptr && runtime->suspendMixer() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_gabrielpc_enginesoundsimulator_audio_FmodNativeBindings_resumeMixer(
    JNIEnv *, jobject, jlong handle) {
    const auto runtime = runtimeFor(handle);
    return runtime != nullptr && runtime->resumeMixer() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_gabrielpc_enginesoundsimulator_audio_FmodNativeBindings_diagnostics(
    JNIEnv *environment, jobject, jlong handle) {
    const auto runtime = runtimeFor(handle);
    return javaString(environment, runtime == nullptr ? "FMOD runtime handle is invalid."
                                                      : runtime->diagnostics());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_gabrielpc_enginesoundsimulator_audio_FmodNativeBindings_lastError(
    JNIEnv *environment, jobject, jlong handle) {
    const auto runtime = runtimeFor(handle);
    if (runtime != nullptr) return javaString(environment, runtime->lastError());
    std::lock_guard<std::mutex> guard(gRegistryMutex);
    return javaString(environment,
        gLastCreateError.empty() ? "FMOD runtime handle is invalid." : gLastCreateError);
}

extern "C" JNIEXPORT void JNICALL
Java_com_gabrielpc_enginesoundsimulator_audio_FmodNativeBindings_release(
    JNIEnv *, jobject, jlong handle) {
    std::shared_ptr<FmodRuntime> runtime;
    {
        std::lock_guard<std::mutex> guard(gRegistryMutex);
        const auto found = gRuntimes.find(handle);
        if (found == gRuntimes.end()) return;
        runtime = std::move(found->second);
        gRuntimes.erase(found);
    }
    runtime->requestValidationAbort();
    runtime->close();
}
