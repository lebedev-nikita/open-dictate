# Post-processing prewarm study

Study date: 2026-10-20. The question is whether preparing the prompt cache before
the transcript is ready meaningfully reduces OpenDictate's post-processing wait.
This is an API experiment; it does not change either application.

## Decision and scope

The completed primary series does not support adding cache prewarm to every
dictation. Its estimated median TTFT saving is -13 ms, with a 95% interval from
-58 to +32 ms. This excludes the predefined useful improvement of at least
200 ms and 10% under the tested conditions. It does not establish that prewarm is
useless for every model, prompt, account, or network. The separate API transport
supplement and independent concurrency replication also did not establish a
reliable speedup. No production prewarm change is justified by this study; both
applications are unchanged.

The earlier exploratory series is a pilot, not evidence pooled into this study.
A useful outcome must improve both the typical delay and the user's wait for a
completed result, without increasing failures. The working product threshold is
at least 200 ms and 10%; this is a study assumption, not an established
perceptual threshold.

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

### Independent replication of the zero-lead anomaly

After the original overlap series revealed an unexpected zero-lead improvement,
a separate fixed replication was specified before its own data collection. This
is a response to that observation, not part of the original confirmatory plan.
Its results were not pooled into the original primary endpoint, and the sample
was not extended according to its outcome.

The replication used 48 four-arm blocks with the same synthetic source within each block:
cold inference; completed same-prefix prewarm then inference; simultaneous
same-prefix prewarm and inference; and simultaneous unrelated-prefix prewarm and
inference. Every arm used a fresh prefix, with the unrelated preparation using a
different fresh prefix from its inference. All 24 arm-order permutations occurred
exactly twice, then were shuffled with seed 20261022. Both transports were warmed;
preparation used its separate connection and the real inference used the main
connection. Concurrent inference started immediately without awaiting preparation.
The study made 336 Responses calls, comprising 192 inferences and 144
preparations, plus two GET transport warmups, after the independent transport
study finishes.

The replication's primary contrast was simultaneous same-prefix preparation
versus cold inference. Completed preparation and unrelated simultaneous
preparation were mechanism controls. The unrelated arm distinguished a
same-prefix interaction from the effect of merely issuing another request.
Cache reads and writes were recorded in both members of every concurrent pair: the
original zero-lead data usually showed inference writing a prefix that the
preparation subsequently read. A reproducible speedup without inference cache
reads would still require a different mechanism explanation; it must not be
described as a demonstrated completed-cache-prewarm benefit.

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

### Primary: completed preparation, warm transport

All 128 RU+EN pairs completed, with timings available in both arms. Medians below
describe each arm separately; the saving and its interval are calculated from
within-pair differences, not by subtracting those two marginal medians. A
positive saving favors prewarm.

| Metric | Cold median | Prewarmed median | Paired median saving | 95% paired bootstrap interval |
| --- | ---: | ---: | ---: | ---: |
| First text token | 845 ms | 854 ms | -13 ms | -58 to +32 ms |
| Completed API response | 1,125 ms | 1,143 ms | -28 ms | -91 to +2 ms |

The paired median TTFT saving is -1.58%, with a 95% interval of -6.74% to +3.80%.
The completion saving is -2.62%, with an interval of -8.67% to +0.21%. Neither
shows a useful typical improvement. The distribution-free sign interval for the
TTFT median difference, -62 to +38 ms, supports the same practical interpretation.
The four wave median TTFT savings were +19, -3, -32, and -40 ms; no wave had a
point estimate close to the 200 ms threshold.

Cache manipulation worked in every primary pair: all cold inferences read zero
cached tokens, all prewarmed inferences read their prefix, and all 128 preparation
calls completed with zero generated output. The median prefix write/read was
2,897 tokens. Preparation itself took a median 757 ms. This runtime was fully
outside the inference timings above, making this a favorable test of completed
preparation; adding a blocking preparation step would not improve those results.

Strict output validation passed for 126/128 cold and 125/128 prewarmed responses;
the limited content probes passed for 125/128 and 124/128 respectively. These
small differences do not establish a causal quality regression. All observed
timings remain in the primary comparison. Restricting to the 123 pairs whose
outputs both passed JSON validation gives a TTFT saving of -18 ms [-66, +26]
and a completion saving of -27 ms [-89, +2]. The stricter 121-pair content-probe
subset leads to the same interpretation. Cold-first and prewarm-first strata
also have intervals crossing zero. A completed API response is not automatically
a usable transformed result.

Primary TTFT p90 was 1,294 ms cold and 1,254 ms prewarmed; the bootstrap interval
for its difference is -282 to +324 ms. Completion p90 was 1,614 versus 1,739 ms,
with a difference interval of -429 to +259 ms. These tail estimates do not
establish an improvement or a regression; the typical-delay conclusion must not
be generalized into a precise tail guarantee.

The supplementary 32-pair RU series estimated a TTFT saving of +57 ms with a
95% interval of -55 to +210 ms, and a completion saving of +82 ms with an interval
of -173 to +157 ms. This smaller series is inconclusive about a 200 ms TTFT
effect and must not replace the predefined RU+EN endpoint. Cache checks passed
for all RU pairs as well.

### Secondary series

The fixed main plan completed all 1,031 calls: 771 inference requests and 260
preparations. Every request reached `completed`, and every inference had a TTFT.
Of the 771 inference outputs, 18 failed JSON/schema validation; 26 failed the
limited content probes, including those invalid outputs. These probes are not a
complete semantic evaluation. The estimated main-run API cost is $0.2213 using
the Standard prices above, excluding pilot, smoke, and transport studies.

All intervals below are exploratory and unadjusted for multiple comparisons.
Entries show paired median savings in milliseconds [95% bootstrap interval]. A
positive value favors the second condition named in the comparison.

| Comparison | Pairs | TTFT saving | Completion saving |
| --- | ---: | ---: | ---: |
| Cold → ordinary prefix repeat | 128 | -5 [-45, +25] | -25 [-70, +19] |
| Ordinary repeat → completed prewarm | 128 | +46 [-29, +75] | +14 [-45, +68] |
| Current → split layout, both cold | 24 | -122 [-207, -17] | -128 [-216, -11] |
| Current → split layout, frequent use | 24 | -81 [-225, +15] | -181 [-316, -57] |
| Ordinary split reuse → extra prewarm | 24 | -102 [-202, +38] | -36 [-162, +80] |
| Cold → completed prewarm, longer source | 16 | -43 [-116, +62] | -201 [-424, -50] |

Ordinary repeats successfully read the cached prefix, as did the prewarmed
requests. Neither natural reuse nor redundant preparation demonstrated the
predefined useful typical speedup. Splitting the current input was not faster in
these samples. The small secondary series cannot establish a universal layout
or quality difference; their less conservative bootstrap intervals also differ
from sign intervals on some endpoints. They provide no reason to change the
production request layout solely to enable prewarm.

### Concurrent preparation

Inference started at source readiness regardless of whether preparation had
finished. Each lead-time group has 12 pairs. The actual median leads were within
5 ms of their targets. A completed preparation is distinct from a cache hit:
the server can make a prefix available before the preparation response finishes.

| Lead | Preparation completed before inference | Inference cache hits | TTFT saving, ms [95% interval] | Completion saving, ms [95% interval] |
| --- | ---: | ---: | ---: | ---: |
| 0 s | 0/12 | 1/12 | +494 [+142, +830] | +460 [+176, +887] |
| 0.5 s | 0/12 | 12/12 | -45 [-408, +231] | -90 [-417, +237] |
| 1 s | 8/12 | 12/12 | -19 [-123, +188] | +21 [-184, +227] |
| 2 s | 12/12 | 12/12 | -64 [-263, +107] | -91 [-239, +63] |
| 5 s | 11/12 | 12/12 | +19 [-121, +210] | +91 [-69, +497] |

The zero-lead result is an unresolved secondary anomaly, not evidence that a
completed cache preparation accelerated inference. Its cold-arm median TTFT was
1,473 ms, compared with 900 ms concurrent and 845 ms in the main cold series;
this cross-series comparison is descriptive, not a matched causal estimate.
Moreover, 10/12 concurrent preparation requests themselves read the prefix
written by inference, while 11/12 inferences reported no cache read. The usual
direction of reuse was therefore inference → preparation. Cache availability
does not explain the observed benefit through the intended preparation →
inference mechanism.

The anomaly was not explained by a simple order imbalance: seven pairs ran cold
first and five concurrent first, with positive subgroup median savings in both.
Its small sample, multiple secondary comparisons, temporal variation, and
unverified concurrency mechanism motivated the independent bounded replication
below. The four other lead-time groups do not show a reliable typical improvement.

As a separate blocking-policy simulation, adding unhidden preparation duration
to the primary prewarmed response gives a median completion saving of -789 ms
[-915, -728] at zero lead and -289 ms [-415, -228] at 0.5 s lead. This is an
arithmetic scenario using measured sequential durations, not an observed
concurrent effect. It reinforces that inference must not wait for preparation.

### Transport supplement

All 72 transport-series inference requests passed JSON validation and read the
seeded cache prefix. The 24 redundant preparation calls also read the prefix and
generated zero output. Each comparison has 24 pairs; savings exclude setup and
show paired medians in milliseconds [exploratory 95% bootstrap interval].

| Comparison | TTFT saving | Completion saving |
| --- | ---: | ---: |
| Fresh connection → GET-prepared | +57 [-102, +202] | +65 [-90, +180] |
| Fresh connection → prewarm-prepared | +84 [-57, +159] | +80 [-237, +178] |
| GET-prepared → prewarm-prepared | +1 [-103, +201] | -8 [-266, +52] |

The median setup duration was 655 ms for GET and 990 ms for prewarm. Those
durations are additional work: the table models setup finishing before the
source becomes ready, not a measured end-to-end saving when setup blocks
generation. Preparation before an otherwise fresh API connection did not show a
reliable improvement in this sample, and Responses prewarm did not demonstrate
an advantage over the simpler GET preparation. Estimated Responses usage cost
was $0.0047 for this supplement, including its seed.

These 24 blocks do not settle all first-session transport effects. The study
uses new HTTP/1.1 connections on the Mac, not the phone's connection pool,
Android HTTP/2, OAuth token renewal, or mobile radio startup. These effects on
the connected ChatGPT route are unmeasured, so the results establish neither a
useful transport benefit nor its absence on the phone.

### Independent concurrency replication

All 48 four-arm blocks completed, with all 24 arm-order permutations represented
twice. The initial approximately 500 ms zero-lead gain was not established
independently. The replication's predefined same-prefix concurrent contrast
estimated a TTFT saving of +73 ms [-464, +255] and a completion saving of -12 ms
[-562, +453]. Both intervals include substantial uncertainty and zero.

| Comparison | TTFT saving, ms [95% interval] | Completion saving, ms [95% interval] |
| --- | ---: | ---: |
| Cold → same-prefix concurrent preparation | +73 [-464, +255] | -12 [-562, +453] |
| Cold → completed preparation | +142 [-182, +218] | +142 [-47, +293] |
| Cold → unrelated-prefix concurrent preparation | -81 [-543, +183] | -109 [-553, +245] |
| Unrelated → same-prefix concurrent preparation | +98 [-142, +260] | +137 [-237, +363] |

Cache checks distinguished the controls: cold read 0/48 prefixes, completed
prewarm read 48/48, same-prefix concurrency read 23/48, and unrelated-prefix
concurrency read 0/48. All 144 preparation requests generated zero output.
JSON validation passed for 47/48 cold, 48/48 completed, 47/48 same-prefix, and
45/48 unrelated-prefix inference outputs; these small counts do not establish a
causal quality difference.

The later replication's cold median TTFT was 1,219 ms, versus 845 ms in the main
series. This temporal variation is one reason the studies remain separate.
Completed preparation and concurrent cache availability did not guarantee a
typical speedup. The replication does not prove that every possible concurrency
effect is absent; it provides no reproducible benefit on which to base an app
change, and the same-prefix versus unrelated control leaves the mechanism
unresolved.

## Artifacts and reproduction

The main, transport, and replication studies together made 1,464 Responses
requests: 1,036 generations and 428 preparations, plus 31 model GET checks.
Every Responses request reached `completed`. Across inference outputs, 23 failed
JSON/schema validation and 32 failed the limited content probes, including
invalid outputs. Estimated total API cost was $0.3195. These totals exclude the
earlier 131-call pilot and 36-call smoke validation.

The [final analysis](prewarm-study-data/final-analysis.json) contains the combined
accounting and separate estimates. Each study retains its fixed plan, environment,
per-request metrics and summaries, without source or response text:

| Study | Plan | Environment | Measurements | Summary |
| --- | --- | --- | --- | --- |
| Main | [plan](prewarm-study-data/main/plan.json) | [environment](prewarm-study-data/main/environment.json) | [metrics](prewarm-study-data/main/measurements.jsonl) | [summary](prewarm-study-data/main/summary.json) |
| Transport | [plan](prewarm-study-data/transport/plan.json) | [environment](prewarm-study-data/transport/environment.json) | [metrics](prewarm-study-data/transport/measurements.jsonl) | [summary](prewarm-study-data/transport/summary.json) |
| Replication | [plan](prewarm-study-data/replication/plan.json) | [environment](prewarm-study-data/replication/environment.json) | [metrics](prewarm-study-data/replication/measurements.jsonl) | [summary](prewarm-study-data/replication/summary.json) |

Recalculate the analysis without credentials or API calls, choosing an unused
output file:

```bash
python3 scripts/analyze-prewarm-study.py \
  --study docs/prewarm-study-data/main \
  --transport docs/prewarm-study-data/transport \
  --replication docs/prewarm-study-data/replication \
  --output /tmp/opendictate-prewarm-analysis-new.json
```

The collectors are [main](../scripts/evaluate-prewarm.py),
[transport](../scripts/evaluate-prewarm-transport.py), and
[replication](../scripts/evaluate-prewarm-race.py). They generate prompt settings
from the [application template helper](../scripts/prewarm-template.swift) and
bundle synthetic sources. A new paid run needs an explicitly selected authorized
API credential; no credential fallback is permitted. The transcription-key
exception used here was authorized only for this study. The
[offline tests](../scripts/test_evaluate_prewarm.py) exercise timing, stream
handling, accounting, cache controls, and the balanced replication design.

Only per-request metrics, summaries and synthetic-case identifiers are retained. No keys,
request or response payloads, user transcripts, or focused-field content belong
in the study artifacts.
