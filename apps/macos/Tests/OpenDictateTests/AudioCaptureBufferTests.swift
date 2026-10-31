import AVFoundation
import OpenDictateAudio
import XCTest

final class AudioCaptureBufferTests: XCTestCase {
    func testFirstWriteTimeIsRecordedOnceForDiagnostics() throws {
        let format = try XCTUnwrap(AVAudioFormat(standardFormatWithSampleRate: 48_000, channels: 1))
        let capture = try XCTUnwrap(ODAudioCaptureBuffer(format: format, capacityFrames: 1_024))
        XCTAssertEqual(capture.firstWriteHostTime, 0)
        let before = mach_absolute_time()
        let input = try samples(format, frames: 128, start: 1)
        capture.write(frames: input.frameLength, from: input.audioBufferList)
        let first = capture.firstWriteHostTime
        XCTAssertGreaterThanOrEqual(first, before)
        XCTAssertLessThanOrEqual(first, mach_absolute_time())
        capture.write(frames: input.frameLength, from: input.audioBufferList)
        XCTAssertEqual(capture.firstWriteHostTime, first)
    }

    func testShortFirstBufferIsReadableWithoutWaitingForFortyMilliseconds() throws {
        let format = try XCTUnwrap(AVAudioFormat(standardFormatWithSampleRate: 48_000, channels: 1))
        let capture = try XCTUnwrap(ODAudioCaptureBuffer(format: format, capacityFrames: 1_024))
        let input = try samples(format, frames: 128, start: 1)
        capture.write(frames: input.frameLength, from: input.audioBufferList)
        let output = try samples(format, frames: 512)
        XCTAssertEqual(capture.read(into: output), 128)
        XCTAssertEqual(output.frameLength, 128)
        XCTAssertEqual(Array(UnsafeBufferPointer(start: output.floatChannelData![0], count: 128)), (1...128).map(Float.init))
        XCTAssertFalse(capture.hasFailed)
    }

    func testWrapPreservesPlanarChannelsAndFinalPartialBuffer() throws {
        let format = try XCTUnwrap(AVAudioFormat(standardFormatWithSampleRate: 48_000, channels: 2))
        let capture = try XCTUnwrap(ODAudioCaptureBuffer(format: format, capacityFrames: 8))
        let output = try samples(format, frames: 4)
        let first = try samples(format, frames: 6, start: 1)
        capture.write(frames: 6, from: first.audioBufferList)
        XCTAssertEqual(capture.read(into: output), 4)
        let next = try samples(format, frames: 6, start: 7)
        capture.write(frames: 6, from: next.audioBufferList)
        var channels = [[Float](), [Float]()]
        while capture.read(into: output) > 0 {
            for channel in 0..<2 {
                channels[channel].append(contentsOf: UnsafeBufferPointer(start: output.floatChannelData![channel], count: Int(output.frameLength)))
            }
        }
        XCTAssertEqual(channels[0], (5...12).map(Float.init))
        XCTAssertEqual(channels[1], (5...12).map { Float($0 + 1_000) })
        XCTAssertFalse(capture.hasFailed)
        let tail = try samples(format, frames: 3, start: 13)
        capture.write(frames: 3, from: tail.audioBufferList)
        XCTAssertEqual(capture.read(into: output), 3)
        XCTAssertEqual(Array(UnsafeBufferPointer(start: output.floatChannelData![0], count: 3)), [13, 14, 15])
        XCTAssertEqual(capture.read(into: output), 0)
    }

    func testInterleavedPCMIsCopiedWithoutReordering() throws {
        let format = try XCTUnwrap(AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: 24_000, channels: 2, interleaved: true))
        let capture = try XCTUnwrap(ODAudioCaptureBuffer(format: format, capacityFrames: 8))
        let input = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 5))
        input.frameLength = 5
        for index in 0..<10 { input.int16ChannelData![0][index] = Int16(index + 1) }
        capture.write(frames: 5, from: input.audioBufferList)
        let output = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 8))
        XCTAssertEqual(capture.read(into: output), 5)
        XCTAssertEqual(Array(UnsafeBufferPointer(start: output.int16ChannelData![0], count: 10)), (1...10).map(Int16.init))
        XCTAssertFalse(capture.hasFailed)
    }

    func testOverflowFailsInsteadOfOverwritingUnreadSpeech() throws {
        let format = try XCTUnwrap(AVAudioFormat(standardFormatWithSampleRate: 48_000, channels: 1))
        let capture = try XCTUnwrap(ODAudioCaptureBuffer(format: format, capacityFrames: 8))
        let first = try samples(format, frames: 6, start: 1)
        let next = try samples(format, frames: 3, start: 7)
        capture.write(frames: 6, from: first.audioBufferList)
        capture.write(frames: 3, from: next.audioBufferList)
        XCTAssertTrue(capture.hasFailed)
        let output = try samples(format, frames: 8)
        XCTAssertEqual(capture.read(into: output), 6)
        XCTAssertEqual(Array(UnsafeBufferPointer(start: output.floatChannelData![0], count: 6)), (1...6).map(Float.init))
        capture.write(frames: 3, from: next.audioBufferList)
        XCTAssertEqual(capture.read(into: output), 0, "A failed capture cannot silently resume with a gap")
    }

    func testMalformedCallbackFailsBeforeReadingOutsideItsBuffers() throws {
        let format = try XCTUnwrap(AVAudioFormat(standardFormatWithSampleRate: 48_000, channels: 1))
        let capture = try XCTUnwrap(ODAudioCaptureBuffer(format: format, capacityFrames: 8))
        let input = try samples(format, frames: 2)
        capture.write(frames: 8, from: input.audioBufferList)
        XCTAssertTrue(capture.hasFailed)
        let output = try samples(format, frames: 8)
        XCTAssertEqual(capture.read(into: output), 0)
    }

    func testCaptureStorageOutlivesItsOwnerWhileSinkStillExists() throws {
        let format = try XCTUnwrap(AVAudioFormat(standardFormatWithSampleRate: 48_000, channels: 1))
        weak var released: ODAudioCaptureBuffer?
        let sink: AVAudioSinkNode = try autoreleasepool {
            let capture = try XCTUnwrap(ODAudioCaptureBuffer(format: format, capacityFrames: 8))
            released = capture
            return capture.makeSinkNode()
        }
        XCTAssertNil(released, "The callback owns the storage without retaining the recorder or wrapper")
        withExtendedLifetime(sink) {}
    }

    func testInvalidOrUnboundedStorageIsRejected() throws {
        let format = try XCTUnwrap(AVAudioFormat(standardFormatWithSampleRate: 48_000, channels: 1))
        XCTAssertNil(ODAudioCaptureBuffer(format: format, capacityFrames: 0))
        XCTAssertNil(ODAudioCaptureBuffer(format: format, capacityFrames: .max))
    }

    func testConcurrentProducerAndConsumerPreserveEveryFrame() throws {
        let format = try XCTUnwrap(AVAudioFormat(standardFormatWithSampleRate: 48_000, channels: 1))
        let totalFrames = 524_288
        let capture = try XCTUnwrap(ODAudioCaptureBuffer(format: format, capacityFrames: AVAudioFrameCount(totalFrames)))
        let input = try samples(format, frames: AVAudioFrameCount(totalFrames), start: 1)
        let output = try samples(format, frames: 257)
        let producer = expectation(description: "producer finished")
        DispatchQueue.global(qos: .userInitiated).async {
            var list = AudioBufferList(mNumberBuffers: 1,
                mBuffers: AudioBuffer(mNumberChannels: 1, mDataByteSize: 256, mData: nil))
            for offset in stride(from: 0, to: totalFrames, by: 64) {
                list.mBuffers.mData = UnsafeMutableRawPointer(input.floatChannelData![0].advanced(by: offset))
                capture.write(frames: 64, from: &list)
            }
            producer.fulfill()
        }
        let deadline = ContinuousClock.now + .seconds(5)
        var received = 0
        while received < totalFrames && ContinuousClock.now < deadline {
            let count = Int(capture.read(into: output))
            if count == 0 { Thread.sleep(forTimeInterval: 0.0001); continue }
            let actual = UnsafeBufferPointer(start: output.floatChannelData![0], count: count)
            XCTAssertTrue(actual.enumerated().allSatisfy { $0.element == Float(received + $0.offset + 1) })
            received += count
        }
        wait(for: [producer], timeout: 5)
        XCTAssertEqual(received, totalFrames)
        XCTAssertFalse(capture.hasFailed)
    }

    private func samples(_ format: AVAudioFormat, frames: AVAudioFrameCount, start: Int = 0) throws -> AVAudioPCMBuffer {
        let buffer = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frames))
        buffer.frameLength = frames
        for channel in 0..<Int(format.channelCount) {
            for frame in 0..<Int(frames) { buffer.floatChannelData![channel][frame] = Float(start + frame + channel * 1_000) }
        }
        return buffer
    }
}
