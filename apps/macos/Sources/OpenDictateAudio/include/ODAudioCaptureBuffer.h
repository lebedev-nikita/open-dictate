#import <AVFAudio/AVFAudio.h>

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
/// Use either this producer or the sink, never both concurrently. No allocation, locks or dispatch.
- (void)writeFrames:(AVAudioFrameCount)frames
    fromBufferList:(const AudioBufferList *)buffers NS_SWIFT_NAME(write(frames:from:));
/// Single consumer, off the audio thread. Sets the destination's frameLength to the frames read.
- (AVAudioFrameCount)readIntoBuffer:(AVAudioPCMBuffer *)buffer NS_SWIFT_NAME(read(into:));
@end

NS_ASSUME_NONNULL_END
