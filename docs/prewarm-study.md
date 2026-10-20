# Post-processing prewarm study

Study date: 2026-10-20. The question is whether preparing the prompt cache before
the transcript is ready meaningfully reduces OpenDictate's post-processing wait.
This is an API experiment; it does not change either application.

## Decision and scope

Results are pending. The earlier exploratory series is a pilot, not evidence
pooled into this study. A useful outcome must improve both the typical delay and
the user's wait for a completed result, without increasing failures. The working
product threshold is at least 200 ms and 10%; this is a study assumption, not an
established perceptual threshold.

The user's phone uses connected ChatGPT. These authorized measurements use the
direct OpenAI API, so they cannot establish the effect on that account's OAuth
route, Android networking, or mobile radio. No phone credentials are accessed.

## Existing application behavior

The Android [request builder](../apps/android/app/src/main/java/com/opendictate/app/network/OpenAiTranscriptionClient.kt)
uses `gpt-6-luna`, Standard service, `reasoning.effort: none`, `store: false`, and
a strict `transformed_text` JSON schema. Formatting instructions and source text
share one user message. The [ChatGPT adapter](../apps/android/app/src/main/java/com/opendictate/app/chatgpt/ChatGPTProtocol.kt)
wraps that input in an array and enables streaming. The [foreground service](../apps/android/app/src/main/java/com/opendictate/app/service/DictationForegroundService.kt)
waits for post-processing before delivering the final text. TTFT is therefore a
diagnostic metric; completed-result latency is the relevant delivery metric.

The experiment generates settings from the actual macOS
[request builder](../apps/macos/Sources/OpenDictateCore/OpenAIProtocol.swift),
which uses the shared formatting rules. It enables punctuation and all available
value formatting with Russian defaults. RU+EN is the primary prompt; RU is a
supplement. These approximate the reported feature choices without claiming to
reproduce unknown language selections or custom rules on the phone.

## Documented mechanism

OpenAI documents output-free `prompt_cache_options.prewarm: true` for GPT-5.6+
and a subsequent request with the same prefix. Cacheable prefixes need 1,024
visible tokens. Explicit mode needs a marked boundary; changing text belongs
after it. Reuse refreshes the default 30-minute lifetime. Hits depend on matching
content and available cache routing. These contracts justify manipulation checks,
not a promised latency improvement. [Prompt caching guide](https://developers.openai.com/api/docs/guides/prompt-caching).

The connected ChatGPT route requires `store: false`, `stream: true`, and array
input; successful streaming requires `response.completed`. Its published limits
do not explicitly establish prewarm availability for every account.
[Plan requirements](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations),
[inference contract](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference).

## Questions and controls

1. Does a completed prewarm reduce cold-prefix TTFT when input layout is fixed?
2. Does ordinary prefix reuse provide the same benefit without a preparation call?
3. Does preparation finish early enough to help short dictations?
4. Is any apparent improvement explained by request layout or temporal variation?

Cold and prewarmed inference use the same split input layout: static developer
instructions followed by a separate user source. An explicit breakpoint ends the
static content. Different fixed-length random markers before the long prompt
isolate the two arms' caches. The marker remains unchanged between preparation
and inference, and between cold inference and its ordinary repeat. A cache key
alone is not treated as proof of a cold prefix.

Pair order is randomized, requests use synthetic text, and the primary series
uses a persistent authenticated transport. Completed preparation before inference
measures the best-case effect; it must not be reported as a production overlap
result. Each pair also has an ordinary follow-up using the cold arm's prefix
and a different synthetic source; this verifies reuse but is a secondary latency
comparison because its source and position differ from the prewarmed inference.

The fixed plan, selected before confirmatory outcomes, is:

| Series | Samples | Purpose |
| --- | --- | --- |
| RU+EN, short source | 128 pairs, four chronological waves of 32 | Primary cold versus prepared contrast |
| RU, short source | 32 pairs, four waves of 8 | Shorter-prompt sensitivity |
| Current versus split layout | 24 cold pairs | Separate input-layout effects |
| Frequent dictation | 24 three-arm blocks | Current, split ordinary reuse, split plus redundant prewarm |
| Longer source | 16 pairs | Completion and output-length sensitivity |
| Concurrent preparation | 12 pairs at each of five lead times | 0, 0.5, 1, 2, and 5 seconds of overlap |

Both language groups are interleaved within each wave. Frequent-series arms use
separate stable prefixes seeded by ordinary inference, rather than prewarming
the ordinary-reuse control. Their changing suffixes prevent identical full
requests. The nominal plan makes 1,031 requests, including 260 preparation calls;
smoke validation is excluded. Overlap uses separate transports and starts
inference when the source is ready, without waiting for preparation. Transport
state and preparation success must be distinguished from merely finishing a
background task. The metric-only plan and environment artifacts are authoritative
for the executed settings.

### Supplement: transport preparation

This independent supplement was specified before analysis of the new timing
outcomes; it does not change the primary sample or pool observations with it.
The primary comparison keeps transport warm in both arms. A first phone session
may instead have a cold connection on the separate ChatGPT client, so absence of
a cache-only benefit would not exclude a transport-preparation benefit.

There are 24 randomized three-arm blocks. A single stable split prefix is first
seeded by ordinary inference. Each arm then creates a fresh HTTP/1.1 HTTPS
connection and uses the same varied synthetic source within its block:

1. Send generation directly on the fresh connection.
2. GET `/v1/models/gpt-6-luna`, then generate on that connection.
3. Send a redundant cache-read prewarm, then generate on that connection.

All inference arms should read the already-seeded prefix. The protocol makes
97 Responses calls: one seed, 72 generations, and 24 preparations. It also makes
25 model GET checks: one seed transport check and 24 GET-arm checks. Setup and
generation durations are measured separately. Compare fresh versus GET-prepared,
fresh versus prewarm-prepared, and GET-prepared versus prewarm-prepared using
paired estimates and exploratory intervals; count every attempt and verify cache
reads and zero-output preparations.

Preparation finishes before generation in this supplement, so post-setup TTFT
alone does not establish an end-to-end improvement. The measured setup duration
must be added when it cannot occur before the source is ready. This supplement
does not execute concurrent recording or source arrival. It isolates API
transport-related effects from a cold-prefix cache write, but does not measure
OAuth token renewal, Android HTTP/2, mobile radio startup, or persistent phone
connection reuse. A benefit after GET or prewarm must therefore remain distinct
from the primary cache-only effect and from a measured phone benefit.

## Analysis rules

TTFT starts immediately before sending the POST and ends at the first nonempty
`response.output_text.delta`, not headers or a metadata event. Completed-result
latency ends at the terminal completed event. First-token times from subsequently
failed requests remain identifiable as failures, rather than successful delivery.
Report every attempt, HTTP/terminal errors, schema failures, output token counts,
and missing token timings. Preparation must generate zero output, cold requests
must show no prefix read, and warmed requests must show the expected prefix read.
Unexpected manipulation outcomes remain in the attempt accounting.

The primary contrast is the median within-pair difference, cold minus prewarmed
TTFT; a positive value is faster. Report paired percentage savings separately
from ratios of marginal medians. Confidence intervals must accompany point
estimates. A lower interval bound above zero supports benefit; establishing the
practical threshold is stronger and requires its bounds to clear both 200 ms and
10%. An interval crossing a threshold is inconclusive, not proof of no benefit.
Completion, p90/p95, errors, cache checks, per-wave effects, and ordinary reuse are
secondary outcomes; avoid selecting the most favorable subgroup as the answer.

The pilot's 20 RU+EN paired differences had median 305 ms and standard deviation
3,033 ms. An illustrative resampling calculation, centering those differences at
zero, gives a 95% range of approximately -331 to +262 ms for the sample median at
128 pairs. This is a fixed-budget precision study, not a powered equivalence
study. The pilot-based calculation is noisy and cannot guarantee resolution of a
200 ms effect. The fixed sample and analysis must be recorded before collecting
new outcomes; do not extend the study only until significance appears.

Chronological waves expose changing service conditions. The paired bootstrap
resamples blocks within each observed wave; report wave estimates and a
distribution-free sign interval for the median as a sensitivity check. These
intervals do not establish between-day uncertainty from four adjacent time
blocks. The earlier pilot is excluded from the primary estimate.

## Costs and limits

The model page lists Standard prices per million tokens: $0.10 uncached input,
$0.01 cache read, $0.125 cache write, and $0.50 output. Batch and Flex are half
Standard; their tables must not be used for interactive Standard estimates.
[GPT-6 Luna pricing](https://developers.openai.com/api/docs/models/gpt-6-luna).

Cost estimates count uncached input, cache reads, cache writes, and output
separately using returned usage. They are estimates, not invoices. A cold split
inference already writes its prefix: prewarm plus the later read adds a cache
read compared with that comparator. Preparing an already warm prefix adds another
read without eliminating the inference request. Cancelled dictations may incur
preparation costs without any useful follow-up.

Synthetic cold prefixes deliberately model first use after settings changes or
cache loss. They do not estimate how often that happens in normal use. A stable
prefix can already be warm during frequent dictation. HTTP transport, one host,
one API account, one date, selected prompt sizes, and synthetic source texts limit
generalization. Cached-token counts verify reuse, not causation of the entire
TTFT: scheduling, network, and decoding still contribute. Schema validity is not
a full semantic-quality evaluation. A production decision also needs the actual
ChatGPT route and the fraction of sessions whose preparation can finish early.

## Results

Pending the fixed-sample run. Report cold, prepared, and ordinary-repeat TTFT and
completion; paired intervals; lead-time results; failures and cache manipulation
checks; preparation duration; input/output usage and estimated cost. Keep only
aggregate metrics and synthetic-case identifiers. Never retain keys, request or
response payloads, user transcripts, or focused-field content.
