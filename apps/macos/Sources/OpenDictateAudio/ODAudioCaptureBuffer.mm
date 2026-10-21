#import "ODAudioCaptureBuffer.h"
#include <atomic>
#include <cstring>
#include <memory>
#include <vector>

namespace {
// Both supported architectures provide native lock-free counters; never fall back to a mutex.
static_assert(std::atomic<uint32_t>::is_always_lock_free);
constexpr size_t maximumStorageBytes = 16 * 1024 * 1024;

class CaptureStorage {
public:
    CaptureStorage(uint32_t capacity, uint32_t bufferCount, uint32_t channelsPerBuffer, uint32_t bytesPerFrame)
        : capacity(capacity), bufferCount(bufferCount), channelsPerBuffer(channelsPerBuffer),
          bytesPerFrame(bytesPerFrame), samples(size_t(capacity) * bufferCount * bytesPerFrame, 0) {}

    void write(uint32_t frames, const AudioBufferList *input) noexcept {
        if (failed.load(std::memory_order_relaxed) || frames == 0) return;
        const auto end = written.load(std::memory_order_relaxed);
        const auto begin = read.load(std::memory_order_acquire);
        if (frames > capacity || end - begin > capacity - frames || !valid(input, frames)) {
            failed.store(1, std::memory_order_release);
            return;
        }
        copy(input, nullptr, end, frames);
        written.store(end + frames, std::memory_order_release);
    }

    uint32_t take(uint32_t maximumFrames, AudioBufferList *output) noexcept {
        const auto begin = read.load(std::memory_order_relaxed);
        const auto end = written.load(std::memory_order_acquire);
        const auto frames = std::min(maximumFrames, end - begin);
        if (frames == 0) return 0;
        if (!valid(output, frames)) {
            failed.store(1, std::memory_order_release);
            return 0;
        }
        copy(nullptr, output, begin, frames);
        read.store(begin + frames, std::memory_order_release);
        return frames;
    }

    bool hasFailed() const noexcept { return failed.load(std::memory_order_acquire) != 0; }

private:
    const uint32_t capacity, bufferCount, channelsPerBuffer, bytesPerFrame;
    std::vector<uint8_t> samples;
    alignas(64) std::atomic<uint32_t> written{0};
    alignas(64) std::atomic<uint32_t> read{0};
    alignas(64) std::atomic<uint32_t> failed{0};

    bool valid(const AudioBufferList *buffers, uint32_t frames) const noexcept {
        if (!buffers || buffers->mNumberBuffers != bufferCount) return false;
        for (uint32_t index = 0; index < bufferCount; ++index) {
            const auto &buffer = buffers->mBuffers[index];
            if (!buffer.mData || buffer.mNumberChannels != channelsPerBuffer ||
                buffer.mDataByteSize < size_t(frames) * bytesPerFrame) return false;
        }
        return true;
    }

    void copy(const AudioBufferList *input, AudioBufferList *output, uint32_t position, uint32_t frames) noexcept {
        const auto offset = position & (capacity - 1);
        const auto firstBytes = size_t(std::min(frames, capacity - offset)) * bytesPerFrame;
        const auto remainingBytes = size_t(frames) * bytesPerFrame - firstBytes;
        for (uint32_t index = 0; index < bufferCount; ++index) {
            auto *plane = samples.data() + size_t(index) * capacity * bytesPerFrame;
            auto *start = plane + size_t(offset) * bytesPerFrame;
            if (input) {
                const auto *source = static_cast<const uint8_t *>(input->mBuffers[index].mData);
                std::memcpy(start, source, firstBytes);
                if (remainingBytes) std::memcpy(plane, source + firstBytes, remainingBytes);
            } else {
                auto *destination = static_cast<uint8_t *>(output->mBuffers[index].mData);
                std::memcpy(destination, start, firstBytes);
                if (remainingBytes) std::memcpy(destination + firstBytes, plane, remainingBytes);
            }
        }
    }
};
}

@implementation ODAudioCaptureBuffer {
    std::shared_ptr<CaptureStorage> _storage;
    AVAudioFormat *_format;
}

- (nullable instancetype)initWithFormat:(AVAudioFormat *)format capacityFrames:(AVAudioFrameCount)frames {
    const auto &description = *format.streamDescription;
    if (frames == 0 || frames > (1u << 20) || description.mFormatID != kAudioFormatLinearPCM ||
        description.mBytesPerFrame == 0 || description.mChannelsPerFrame == 0) return nil;
    // A power-of-two capacity also keeps the ring position correct when a counter wraps.
    uint32_t capacity = 1;
    while (capacity < frames) capacity <<= 1;
    const uint32_t bufferCount = format.interleaved ? 1 : description.mChannelsPerFrame;
    const uint32_t channelsPerBuffer = format.interleaved ? description.mChannelsPerFrame : 1;
    const auto planeBytes = size_t(capacity) * description.mBytesPerFrame;
    if (planeBytes > maximumStorageBytes || bufferCount > maximumStorageBytes / planeBytes) return nil;
    self = [super init];
    if (self) {
        _format = format;
        try {
            _storage = std::make_shared<CaptureStorage>(capacity, bufferCount, channelsPerBuffer, description.mBytesPerFrame);
        } catch (const std::bad_alloc &) {
            return nil;
        }
    }
    return self;
}

- (BOOL)hasFailed { return _storage->hasFailed(); }

- (AVAudioSinkNode *)makeSinkNode {
    auto storage = _storage;
    // Capturing shared_ptr extends storage lifetime when the block is created. Invoking the
    // block only dereferences it: no ARC, allocation, refcount updates, locks or queue calls.
    return [[AVAudioSinkNode alloc] initWithReceiverBlock:^OSStatus(const AudioTimeStamp *, AVAudioFrameCount frames,
                                                                 const AudioBufferList *input) {
        storage->write(frames, input);
        return noErr;
    }];
}

- (void)writeFrames:(AVAudioFrameCount)frames fromBufferList:(const AudioBufferList *)buffers {
    _storage->write(frames, buffers);
}

- (AVAudioFrameCount)readIntoBuffer:(AVAudioPCMBuffer *)buffer {
    if (![buffer.format isEqual:_format]) { buffer.frameLength = 0; return 0; }
    buffer.frameLength = buffer.frameCapacity;
    const auto frames = _storage->take(buffer.frameCapacity, buffer.mutableAudioBufferList);
    buffer.frameLength = frames;
    return frames;
}
@end
