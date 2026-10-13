# Transcription punctuation checks

Run real file transcription requests without installing or changing either app.
Requires Python 3.9+ and an `OPENAI_API_KEY` in the root `.env` file or environment.
An existing env file takes precedence. The runner uses only the Python standard
library and sends audio directly to OpenAI.

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
additional checks of the RC2 prompt:

```bash
python3 scripts/evaluate-transcription-punctuation.py \
  --audio /tmp/punctuation-input.wav \
  --reference-file /tmp/punctuation-reference.txt \
  --repeats 5 --confirm rc2 --confirmation-repeats 10 \
  --report /tmp/punctuation-results.json
```

Each trial makes one API request. Output and reports contain comma positions,
word-preservation checks and request timings, without keys, audio, transcripts
or raw API payloads. The bootstrap reference is the only saved transcript; keep
it outside Git. Existing reference and report files are never overwritten.

Defaults reproduce the RC2 Accurate prompt with Russian and English language
hints and an empty user dictionary. Use `--languages`, `--model` and
`--prompts-file` to test another configuration. Compare comma positions against
an independently checked reference; capitalization and Russian е/ё differences
are ignored when comparing words. These checks cover Latin/Cyrillic prose, not
language-independent tokenization or the correctness of other punctuation.

Request timings include networking from this computer. They do not measure
Android microphone capture or insertion latency. Passing one recording does
not establish accuracy on other recordings or languages.

Run the scorer and credential-handling tests without API calls:

```bash
python3 -m unittest discover -s scripts -p 'test_*.py'
```

Delete the temporary WAV after testing.
