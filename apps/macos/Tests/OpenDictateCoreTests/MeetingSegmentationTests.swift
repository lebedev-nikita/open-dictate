import XCTest
@testable import OpenDictateCore

/// Synthetic PCM: a 440 Hz tone stands in for speech; silence is digital zero or quiet noise.
enum SyntheticAudio {
    static let rate = AudioConstants.sampleRate

    static func tone(_ millis: Int, amplitude: Double = 0.3) -> Data {
        let count = rate * millis / 1_000
        var samples = [Int16](repeating: 0, count: count)
        for index in 0..<count {
            samples[index] = Int16(amplitude * 32_767 * sin(2 * .pi * 440 * Double(index) / Double(rate)))
        }
        return samples.withUnsafeBytes { Data($0) }
    }

    static func silence(_ millis: Int) -> Data { Data(count: rate * millis / 1_000 * 2) }

    /// Deterministic pseudo-random noise at a fixed RMS level.
    static func noise(_ millis: Int, amplitude: Double) -> Data {
        var state: UInt32 = 12_345
        // Explicit steps: Xcode 16 cannot type-check this as one expression.
        var samples = [Int16](repeating: 0, count: rate * millis / 1_000)
        for index in samples.indices {
            state = state &* 1_664_525 &+ 1_013_904_223
            let unit: Double = Double(state) / Double(UInt32.max)
            let scaled: Double = (unit * 2 - 1) * amplitude * 32_767
            samples[index] = Int16(scaled)
        }
        return samples.withUnsafeBytes { Data($0) }
    }
}

final class MeetingSegmentationTests: XCTestCase {
    private func segment(_ parts: [Data], configuration: UtteranceSegmenter.Configuration = .init()) -> [UtteranceSegmenter.Utterance] {
        var segmenter = UtteranceSegmenter(configuration: configuration)
        var result = [UtteranceSegmenter.Utterance]()
        // Feed in 40 ms chunks, like the capture pipeline.
        let audio = parts.reduce(Data(), +)
        var offset = 0
        while offset < audio.count {
            let end = min(audio.count, offset + AudioConstants.chunkBytes)
            result += segmenter.append(audio.subdata(in: offset..<end))
            offset = end
        }
        return result + segmenter.finish()
    }

    private func millis(_ samples: Int) -> Int { samples * 1_000 / AudioConstants.sampleRate }

    func testSilenceIsNeverEmitted() {
        XCTAssertTrue(segment([SyntheticAudio.silence(5_000)]).isEmpty)
        XCTAssertTrue(segment([SyntheticAudio.noise(5_000, amplitude: 0.003)]).isEmpty)
    }

    func testPausesSplitUtterancesAndStampTheirSpeechStart() {
        let utterances = segment([SyntheticAudio.silence(1_000), SyntheticAudio.tone(1_500),
                                  SyntheticAudio.silence(1_200), SyntheticAudio.tone(800), SyntheticAudio.silence(1_000)])
        XCTAssertEqual(utterances.count, 2)
        XCTAssertEqual(millis(utterances[0].speechStartSample), 1_000, accuracy: 20)
        XCTAssertEqual(millis(utterances[1].speechStartSample), 3_700, accuracy: 20)
        // Pre-roll and tail surround the speech so soft onsets and endings survive.
        XCTAssertEqual(millis(utterances[0].audioStartSample), 700, accuracy: 20)
        XCTAssertEqual(utterances[0].durationMillis, 300 + 1_500 + 300, accuracy: 40)
        XCTAssertEqual(utterances[1].speechMillis, 800, accuracy: 40)
    }

    func testPausesShorterThanTheThresholdKeepOneUtterance() {
        let utterances = segment([SyntheticAudio.tone(1_000), SyntheticAudio.silence(500), SyntheticAudio.tone(1_000)])
        XCTAssertEqual(utterances.count, 1)
        XCTAssertEqual(utterances[0].speechMillis, 2_000, accuracy: 40)
    }

    func testClicksShorterThanTheMinimumSpeechAreDropped() {
        let utterances = segment([SyntheticAudio.silence(500), SyntheticAudio.tone(100), SyntheticAudio.silence(1_500),
                                  SyntheticAudio.tone(400), SyntheticAudio.silence(1_000)])
        XCTAssertEqual(utterances.count, 1)
        XCTAssertEqual(millis(utterances[0].speechStartSample), 2_100, accuracy: 20)
    }

    func testSteadyBackgroundNoiseRaisesTheThreshold() {
        // Noise at about −40 dBFS is louder than the absolute floor but carries no speech.
        let noise = SyntheticAudio.noise(12_000, amplitude: 0.017)
        XCTAssertTrue(segment([noise]).isEmpty)
        let speech = segment([noise, SyntheticAudio.tone(1_000), SyntheticAudio.noise(1_500, amplitude: 0.017)])
        XCTAssertEqual(speech.count, 1)
        XCTAssertEqual(millis(speech[0].speechStartSample), 12_000, accuracy: 40)
    }

    func testLongSpeechIsCutWithoutLosingAudio() {
        let speech = SyntheticAudio.tone(70_000)
        let utterances = segment([speech])
        XCTAssertEqual(utterances.count, 3)
        XCTAssertTrue(utterances.allSatisfy { $0.durationMillis <= 30_000 })
        // Consecutive pieces continue exactly where the previous one ended.
        for (previous, next) in zip(utterances, utterances.dropFirst()) {
            XCTAssertEqual(previous.audioStartSample + previous.pcm.count / 2, next.audioStartSample)
        }
        XCTAssertEqual(utterances.reduce(0) { $0 + $1.pcm.count }, speech.count)
    }

    func testAfterTheSoftLimitAShortPauseEndsTheUtterance() {
        let utterances = segment([SyntheticAudio.tone(21_000), SyntheticAudio.silence(400), SyntheticAudio.tone(2_000)])
        XCTAssertEqual(utterances.count, 2)
        XCTAssertEqual(utterances[0].speechMillis, 21_000, accuracy: 40)
    }

    func testFinishEmitsTheUtteranceInProgressAndResetDiscardsIt() {
        var segmenter = UtteranceSegmenter()
        XCTAssertTrue(segmenter.append(SyntheticAudio.tone(1_000)).isEmpty)
        XCTAssertTrue(segmenter.isInUtterance)
        XCTAssertEqual(segmenter.finish().count, 1)
        _ = segmenter.append(SyntheticAudio.tone(1_000))
        segmenter.reset()
        XCTAssertTrue(segmenter.finish().isEmpty)
    }

    func testLevelsAreDecibelsOfFullScale() {
        XCTAssertEqual(UtteranceSegmenter.level(SyntheticAudio.silence(20)), -100)
        // A sine at amplitude A has RMS A/√2: 0.3 → about −13.5 dBFS.
        XCTAssertEqual(UtteranceSegmenter.level(SyntheticAudio.tone(100, amplitude: 0.3)), -13.5, accuracy: 0.3)
    }
}
