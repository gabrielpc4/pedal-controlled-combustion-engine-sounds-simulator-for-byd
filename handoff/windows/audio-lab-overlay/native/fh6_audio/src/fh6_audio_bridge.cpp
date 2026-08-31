#include <fmod.hpp>
#include <fmod_studio.hpp>
#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <vector>

#ifdef _WIN32
#define FH6_EXPORT extern "C" __declspec(dllexport)
#else
#define FH6_EXPORT extern "C"
#endif

namespace {
FMOD::Studio::System* studio = nullptr;
FMOD::System* core = nullptr;
char last_error[256] = "not initialized";
std::vector<FMOD::Studio::Bank*> banks;

int fail(FMOD_RESULT result, const char* operation) {
  std::snprintf(last_error, sizeof(last_error), "%s failed with FMOD_RESULT %d", operation, static_cast<int>(result));
  return static_cast<int>(result);
}
}

FH6_EXPORT const char* fh6_audio_last_error() { return last_error; }

FH6_EXPORT unsigned int fh6_audio_runtime_version() {
  unsigned int version = 0;
  if (core) core->getVersion(&version);
  return version;
}

FH6_EXPORT int fh6_audio_open(int sample_rate, int block_size) {
  if (studio) return 0;
  FMOD_RESULT result = FMOD::Studio::System::create(&studio);
  if (result != FMOD_OK) return fail(result, "Studio::System::create");
  result = studio->getCoreSystem(&core);
  if (result != FMOD_OK) return fail(result, "getCoreSystem");
  result = core->setSoftwareFormat(sample_rate, FMOD_SPEAKERMODE_STEREO, 0);
  if (result != FMOD_OK) return fail(result, "setSoftwareFormat");
  result = core->setDSPBufferSize(static_cast<unsigned int>(std::max(64, block_size)), 4);
  if (result != FMOD_OK) return fail(result, "setDSPBufferSize");
  result = studio->initialize(256, FMOD_STUDIO_INIT_NORMAL, FMOD_INIT_NORMAL, nullptr);
  if (result != FMOD_OK) return fail(result, "initialize");
  std::strcpy(last_error, "ready");
  return 0;
}

FH6_EXPORT int fh6_audio_load_bank(const char* path, FMOD::Studio::Bank** output) {
  if (!studio || !path || !output) return -1;
  const FMOD_RESULT result = studio->loadBankFile(path, FMOD_STUDIO_LOAD_BANK_NORMAL, output);
  return result == FMOD_OK ? 0 : fail(result, "loadBankFile");
}

FH6_EXPORT int fh6_audio_load_powertrain_banks(
    const char* master,
    const char* master_strings,
    const char* master_assets,
    const char* modular_car,
    const char* modular_car_assets) {
  if (!studio) return -1;
  const char* ordered[] = {master, master_strings, master_assets, modular_car, modular_car_assets};
  for (const char* path : ordered) {
    if (!path || !*path) continue;
    FMOD::Studio::Bank* bank = nullptr;
    const FMOD_RESULT result = studio->loadBankFile(path, FMOD_STUDIO_LOAD_BANK_NORMAL, &bank);
    if (result != FMOD_OK) return fail(result, "ordered loadBankFile");
    banks.push_back(bank);
  }
  return 0;
}

FH6_EXPORT int fh6_audio_update() {
  if (!studio) return -1;
  const FMOD_RESULT result = studio->update();
  return result == FMOD_OK ? 0 : fail(result, "update");
}

FH6_EXPORT void fh6_audio_close() {
  if (studio) {
    for (auto iterator = banks.rbegin(); iterator != banks.rend(); ++iterator) {
      if (*iterator) (*iterator)->unload();
    }
    banks.clear();
    studio->unloadAll();
    studio->release();
  }
  studio = nullptr;
  core = nullptr;
  std::strcpy(last_error, "closed");
}
