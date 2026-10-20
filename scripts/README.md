# Transcription punctuation checks

Run real file transcription requests without installing or changing either app.
Requires Python 3.9+ and a `TRANSCRIBE_KEY` in the root `.env` file or environment.
An existing env file takes precedence. The runner uses only the Python standard
library and sends audio directly to `https://api.openai.com/v1`, ignoring
`OPENAI_BASE_URL`. `OPENAI_API_KEY` and `OPENAI_BASE_URL` are reserved for text
processing through the local endpoint; this runner never uses the text key.

For Android-like audio, convert a supplied recording to mono PCM16 WAV at 24 kHz:

```bash
ffmpeg -i recording.m4a -ac 1 -ar 24000 -c:a pcm_s16le /tmp/punctuation-input.wav
```

Create one private reference template, then manually correct its punctuation
while preserving the spoken words:

```bash
python3 scripts/evaluate-transcription-punctuation.py \
  --audio /tmp/punctuation-input.wav \
  --reference-template /tmp/punctuation-reference.txt
```

Compare the bundled prompts five times each in shuffled order, then run ten
additional checks of the current app prompt:

```bash
python3 scripts/evaluate-transcription-punctuation.py \
  --audio /tmp/punctuation-input.wav \
  --reference-file /tmp/punctuation-reference.txt \
  --repeats 5 --confirm punctuation_examples --confirmation-repeats 10 \
  --report /tmp/punctuation-results.json
```

Each trial makes one API request. Output and reports contain comma positions,
word-preservation checks and request timings, without keys, audio, transcripts
or raw API payloads. The bootstrap reference is the only saved transcript; keep
it outside Git. Existing reference and report files are never overwritten.

Defaults reproduce the current Accurate prompt with Russian and English language
hints and an empty user dictionary. Use `--languages`, `--model` and
`--prompts-file` to test another configuration. Compare comma positions against
an independently checked reference that accounts for valid punctuation variants.
Capitalization and Russian е/ё differences are ignored when comparing words.
These checks cover Latin/Cyrillic prose, not
language-independent tokenization or the correctness of other punctuation.

Request timings include networking from this computer. They do not measure
Android microphone capture or insertion latency. Passing one recording does
not establish accuracy on other recordings or languages.

Run the scorer and credential-handling tests without API calls:

```bash
python3 -m unittest discover -s scripts -p 'test_*.py'
```

Delete the temporary WAV after testing.

## Post-processing prewarm study

`evaluate-prewarm.py` compares cold prefixes, completed and concurrent prewarm,
ordinary cache reuse, and the current versus split message layout. It generates
the punctuation/value-formatting prompt from `OpenDictateCore`, so macOS and
Swift are required. Only bundled synthetic text is sent; reports contain timings,
token counts and boolean checks, without request/response bodies or output text.

The default fixed plan makes 1,031 requests to the direct OpenAI Responses API,
using `gpt-6-luna`, Standard service and no reasoning. It ignores
`OPENAI_BASE_URL`; supply a direct API credential as `OPENAI_API_KEY` in the
ignored `.env` file. Missing credentials fail without fallback. The separate
transcription-key override requires explicit user authorization for that run;
it is not a general change to the credential policy.

```bash
python3 scripts/evaluate-prewarm.py --smoke --output /tmp/prewarm-validation
python3 scripts/evaluate-prewarm.py --output /tmp/prewarm-study
```

The smoke run validates the harness and must not be pooled into the main sample.
Output directories must be new. The runner records the fixed plan before API
calls, appends every attempt to `measurements.jsonl`, and writes `summary.json`.
Existing results are never overwritten. To reanalyze, copy the metric directory,
remove its generated summary, and run:

```bash
python3 scripts/evaluate-prewarm.py --analyze /tmp/prewarm-study-copy
```

Timing includes networking from this computer. Cache hits alone do not establish
speedup, and direct API results do not establish connected ChatGPT performance.
See [the study](../docs/prewarm-study.md) for controls, uncertainty and findings.

The independent connection control uses a shared, already cached prefix and a
fresh connection for each of three arms: no setup, an authenticated model GET,
or an extra prewarm. Its 24 blocks make 97 Responses requests and 25 model GETs:

```bash
python3 scripts/evaluate-prewarm-transport.py --output /tmp/prewarm-transport
python3 scripts/analyze-prewarm-study.py \
  --study /tmp/prewarm-study --transport /tmp/prewarm-transport \
  --output /tmp/prewarm-analysis.json
```

The final analysis includes valid-output and request-order sensitivity, p90
intervals, measured overlap readiness, and a clearly labeled simulation of a
blocking preparation policy. It makes no network requests. All reported secondary
intervals are exploratory and do not adjust for multiple comparisons.
