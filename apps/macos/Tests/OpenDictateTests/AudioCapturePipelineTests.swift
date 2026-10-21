import AVFoundation
import Foundation
import OpenDictateCore
import XCTest
@testable import OpenDictate

private final class CaptureEvents: @unchecked Sendable {
    var readyCount = 0
    var chunks = [Data]()
    var levels = [Float]()
    // The production pipeline invokes all callbacks on its single consumer worker.
    func pipeline(format: AVAudioFormat, keepAudio: Bool = true) throws -> AudioCapturePipeline {
        try AudioCapturePipeline(format: format, keepAudio: keepAudio, onReady: { self.readyCount += 1 },
            onChunk: { self.chunks.append($0) }, onLevel: { self.levels.append($0) })
    }
}

final class AudioCapturePipelineTests: XCTestCase {
    func testReadinessPrecedesFirstNetworkChunkAndKeepsInitialSamples() throws {
        let format = try pcmFormat(), events = CaptureEvents()
        let pipeline = try events.pipeline(format: format)
        let input = try pcm(format, frames: 240, start: 1)
        pipeline.buffer.write(frames: input.frameLength, from: input.audioBufferList)
        try pipeline.drain()
        XCTAssertEqual(events.readyCount, 1)
        XCTAssertTrue(events.chunks.isEmpty)
        XCTAssertTrue(events.levels.isEmpty)
        let (data, bytes) = try pipeline.finish()
        XCTAssertEqual(bytes, 480)
        XCTAssertEqual(data, Data(bytes: input.int16ChannelData![0], count: 480))
        XCTAssertEqual(events.chunks, [data])
        XCTAssertEqual(events.readyCount, 1)
    }

    func testStopDrainsQueuedAudioAndEmitsFinalPartialChunkOnce() throws {
        let format = try pcmFormat(), events = CaptureEvents()
        let pipeline = try events.pipeline(format: format)
        let input = try pcm(format, frames: 1_200, start: 1)
        pipeline.buffer.write(frames: input.frameLength, from: input.audioBufferList)
        let (data, bytes) = try pipeline.finish()
        XCTAssertEqual(data, Data(bytes: input.int16ChannelData![0], count: 2_400))
        XCTAssertEqual(bytes, data.count)
        XCTAssertEqual(events.chunks.map(\.count), [1_920, 480])
        XCTAssertEqual(events.chunks.reduce(Data(), +), data)
        XCTAssertEqual(events.readyCount, 1)
        XCTAssertEqual(events.levels.count, 1)
        XCTAssertEqual(try pipeline.finish().1, 0)
        XCTAssertEqual(events.chunks.count, 2)
    }

    func testDiscardedAudioStillStreamsAndCountsBytes() throws {
        let format = try pcmFormat(), events = CaptureEvents()
        let pipeline = try events.pipeline(format: format, keepAudio: false)
        let input = try pcm(format, frames: 960)
        pipeline.buffer.write(frames: input.frameLength, from: input.audioBufferList)
        let (data, bytes) = try pipeline.finish()
        XCTAssertTrue(data.isEmpty)
        XCTAssertEqual(bytes, 1_920)
        XCTAssertEqual(events.chunks.map(\.count), [1_920])
    }

    func testEmptyStopDoesNotAdvertiseReadinessOrProducePadding() throws {
        let events = CaptureEvents(), pipeline = try events.pipeline(format: pcmFormat())
        XCTAssertEqual(try pipeline.finish().1, 0)
        XCTAssertEqual(events.readyCount, 0)
        XCTAssertTrue(events.chunks.isEmpty)
    }

    func testOverflowRejectsTruncatedRecording() throws {
        let format = try pcmFormat(), events = CaptureEvents(), pipeline = try events.pipeline(format: format)
        let input = try pcm(format, frames: 960)
        pipeline.buffer.write(frames: input.frameLength, from: input.audioBufferList)
        try pipeline.drain()
        pipeline.buffer.write(frames: 1_000_000, from: input.audioBufferList)
        XCTAssertThrowsError(try pipeline.drain())
        XCTAssertThrowsError(try pipeline.finish(), "Previously captured speech must not be returned as a complete recording")
        XCTAssertEqual(pipeline.retainedAudio.count, 1_920, "Keep the valid prefix for explicit retry after a capture failure")
    }

    func testRecordingLimitStillFinishesAndFlushesTheAcceptedAudio() throws {
        let format = try pcmFormat(), events = CaptureEvents()
        let pipeline = try events.pipeline(format: format, keepAudio: false)
        let input = try pcm(format, frames: 4_096)
        var reachedLimit = false
        for _ in 0..<3_000 {
            pipeline.buffer.write(frames: input.frameLength, from: input.audioBufferList)
            do { try pipeline.drain() }
            catch DictationError.tooLong { reachedLimit = true; break }
        }
        XCTAssertTrue(reachedLimit)
        let (_, bytes) = try pipeline.finish()
        XCTAssertLessThanOrEqual(bytes, AudioConstants.maximumRecordingBytes)
        XCTAssertGreaterThan(bytes, AudioConstants.maximumRecordingBytes - AudioConstants.chunkBytes)
        XCTAssertEqual(events.chunks.reduce(0) { $0 + $1.count }, bytes)
        XCTAssertEqual(events.readyCount, 1)
    }

    func testResamplingSmallHardwareBuffersMatchesContinuousInputIncludingTail() throws {
        let format = try XCTUnwrap(AVAudioFormat(standardFormatWithSampleRate: 48_000, channels: 2))
        func run(framesPerCallback: Int) throws -> Data {
            let events = CaptureEvents(), pipeline = try events.pipeline(format: format)
            for start in stride(from: 0, to: 9_600, by: framesPerCallback) {
                let frames = min(framesPerCallback, 9_600 - start)
                let input = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(frames)))
                input.frameLength = AVAudioFrameCount(frames)
                for index in 0..<frames {
                    let value = Float(sin(Double(start + index) * 0.07) * 0.2)
                    input.floatChannelData![0][index] = value
                    input.floatChannelData![1][index] = value * 0.5
                }
                pipeline.buffer.write(frames: input.frameLength, from: input.audioBufferList)
                try pipeline.drain()
            }
            let (data, bytes) = try pipeline.finish()
            XCTAssertEqual(bytes, data.count)
            XCTAssertEqual(events.chunks.reduce(Data(), +), data)
            XCTAssertEqual(events.readyCount, 1)
            return data
        }
        let continuous = try run(framesPerCallback: 9_600)
        let small = try run(framesPerCallback: 128)
        XCTAssertEqual(small, continuous)
        XCTAssertGreaterThanOrEqual(small.count, 9_600, "Include the converter's delayed tail")
    }

    private func pcmFormat() throws -> AVAudioFormat {
        try XCTUnwrap(AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: 24_000, channels: 1, interleaved: true))
    }

    private func pcm(_ format: AVAudioFormat, frames: AVAudioFrameCount, start: Int = 0) throws -> AVAudioPCMBuffer {
        let input = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frames))
        input.frameLength = frames
        for index in 0..<Int(frames) { input.int16ChannelData![0][index] = Int16((start + index) % 32_000) }
        return input
    }
}
