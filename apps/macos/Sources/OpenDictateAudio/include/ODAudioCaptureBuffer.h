#import <AVFAudio/AVFAudio.h>
#import <CoreAudio/CoreAudio.h>

NS_ASSUME_NONNULL_BEGIN

/// Bounded single-producer/single-consumer storage. Create and destroy off the audio thread.
/// The sink owns its storage independently, so an engine teardown cannot leave a dangling callback.
@interface ODAudioCaptureBuffer : NSObject
- (instancetype)init NS_UNAVAILABLE;
- (nullable instancetype)initWithFormat:(AVAudioFormat *)format
                        capacityFrames:(AVAudioFrameCount)capacityFrames NS_DESIGNATED_INITIALIZER;
@property(nonatomic, readonly) BOOL hasFailed;
/// mach_absolute_time() of the first stored frames, or 0. Diagnostics only.
@property(nonatomic, readonly) uint64_t firstWriteHostTime;
- (AVAudioSinkNode *)makeSinkNode;
/// Hardware may change between reading its format and connecting. Contain AVFAudio's
/// Objective-C format exception so Swift can retry with a fresh graph.
- (BOOL)connectInputOfEngine:(AVAudioEngine *)engine toSink:(AVAudioSinkNode *)sink
    NS_SWIFT_NAME(connectInput(of:to:));
/// A Core Audio IOProc block for an aggregate device whose input buffers starting at
/// `firstBuffer` carry this format, such as a process tap. Copies them into the ring with no
/// allocation, locks or dispatch. Use either this producer or the sink, never both.
- (AudioDeviceIOBlock)makeDeviceIOBlockWithFirstBuffer:(UInt32)firstBuffer
    NS_SWIFT_NAME(makeDeviceIOBlock(firstBuffer:));
/// Use either this producer or the sink, never both concurrently. No allocation, locks or dispatch.
- (void)writeFrames:(AVAudioFrameCount)frames
    fromBufferList:(const AudioBufferList *)buffers NS_SWIFT_NAME(write(frames:from:));
/// Single consumer, off the audio thread. Sets the destination's frameLength to the frames read.
- (AVAudioFrameCount)readIntoBuffer:(AVAudioPCMBuffer *)buffer NS_SWIFT_NAME(read(into:));
@end

NS_ASSUME_NONNULL_END
