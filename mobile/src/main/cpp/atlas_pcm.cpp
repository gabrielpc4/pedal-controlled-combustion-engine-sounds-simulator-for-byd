#include <jni.h>
#include <sys/mman.h>
#include <unistd.h>

#include <cerrno>
#include <cstdint>
#include <cstring>
#include <limits>
#include <string>

namespace {

void throwIOException(JNIEnv* env, const char* operation) {
    jclass exceptionClass = env->FindClass("java/io/IOException");
    if (exceptionClass == nullptr) {
        return;
    }
    std::string message(operation);
    message += ": ";
    message += std::strerror(errno);
    env->ThrowNew(exceptionClass, message.c_str());
}

bool mappingBounds(
    jlong offset,
    jlong length,
    off_t* alignedOffset,
    size_t* delta,
    size_t* mappedLength
) {
    if (offset < 0 || length <= 0) {
        return false;
    }
    const long pageSize = sysconf(_SC_PAGESIZE);
    if (pageSize <= 0) {
        return false;
    }
    const jlong pageMask = static_cast<jlong>(pageSize - 1);
    const jlong aligned = offset & ~pageMask;
    const jlong offsetDelta = offset - aligned;
    const auto unsignedDelta = static_cast<std::uint64_t>(offsetDelta);
    const auto unsignedLength = static_cast<std::uint64_t>(length);
    if (unsignedDelta > std::numeric_limits<size_t>::max() ||
        unsignedLength > std::numeric_limits<size_t>::max() - unsignedDelta) {
        return false;
    }
    *alignedOffset = static_cast<off_t>(aligned);
    *delta = static_cast<size_t>(offsetDelta);
    *mappedLength = static_cast<size_t>(offsetDelta + length);
    return true;
}

}  // namespace

extern "C" JNIEXPORT jobject JNICALL
Java_com_gabrielpc_enginesoundsimulator_audio_NativeAtlasMemory_map(
    JNIEnv* env,
    jclass,
    jint fileDescriptor,
    jlong offset,
    jlong length
) {
    off_t alignedOffset = 0;
    size_t delta = 0;
    size_t mappedLength = 0;
    if (!mappingBounds(offset, length, &alignedOffset, &delta, &mappedLength)) {
        errno = EINVAL;
        throwIOException(env, "Invalid atlas mmap range");
        return nullptr;
    }
    void* mapped = mmap(nullptr, mappedLength, PROT_READ, MAP_PRIVATE, fileDescriptor, alignedOffset);
    if (mapped == MAP_FAILED) {
        throwIOException(env, "Atlas mmap failed");
        return nullptr;
    }
    void* requested = static_cast<void*>(static_cast<std::uint8_t*>(mapped) + delta);
    jobject buffer = env->NewDirectByteBuffer(requested, length);
    if (buffer == nullptr) {
        munmap(mapped, mappedLength);
        return nullptr;
    }
    return buffer;
}

extern "C" JNIEXPORT void JNICALL
Java_com_gabrielpc_enginesoundsimulator_audio_NativeAtlasMemory_unmap(
    JNIEnv* env,
    jclass,
    jobject buffer,
    jlong offset,
    jlong length
) {
    auto* requested = static_cast<std::uint8_t*>(env->GetDirectBufferAddress(buffer));
    if (requested == nullptr) {
        errno = EINVAL;
        throwIOException(env, "Atlas buffer is not direct");
        return;
    }
    off_t alignedOffset = 0;
    size_t delta = 0;
    size_t mappedLength = 0;
    if (!mappingBounds(offset, length, &alignedOffset, &delta, &mappedLength)) {
        errno = EINVAL;
        throwIOException(env, "Invalid atlas munmap range");
        return;
    }
    void* mapped = static_cast<void*>(requested - delta);
    if (munmap(mapped, mappedLength) != 0) {
        throwIOException(env, "Atlas munmap failed");
    }
}
