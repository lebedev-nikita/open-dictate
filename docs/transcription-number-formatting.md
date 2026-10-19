# Transcription number formatting

Checked against official OpenAI documentation on 2026-10-19. This note separates
documented prompting support from observed behavior and application formatting.

The broader [prompt-behavior investigation](transcription-prompt-behavior.md)
subsequently confirmed lexical effects in both current models, but no numeric
formatting effect from commands or transcript-style examples. Its repeated
`gpt-4o-transcribe` digits condition succeeded in 2/3 trials rather than the
initial 3/3 below. Treat the initial result as a small successful series, not a
formatting guarantee.

## What the documentation supports

- **File transcription:** the [Prompting section](https://developers.openai.com/api/docs/guides/speech-to-text#prompting)
  explicitly includes formatting among the uses of a prompt for `gpt-transcribe`.
  Its examples cover punctuation, capitalization, filler words and writing
  systems. It also confirms prompt support for existing `gpt-4o-transcribe` and
  `gpt-4o-mini-transcribe` integrations; diarization does not support prompts.
- **Both current models:** the [transcription overview](https://developers.openai.com/api/docs/guides/transcription#improve-transcription-quality)
  describes `prompt` as free-form recording context for `gpt-transcribe` and
  `gpt-live-transcribe`. It advises relevant context rather than restating the
  transcription task. `keywords` are recognition hints, not required output.
- **Live transcription:** the [Realtime guide](https://developers.openai.com/api/docs/guides/realtime-transcription#add-transcription-context)
  puts the prompt in `session.audio.input.transcription.prompt` and describes
  recording context. Its production checklist explicitly calls for evaluating
  numbers, dates and currency. The
  [model page](https://developers.openai.com/api/docs/models/gpt-live-transcribe)
  likewise advertises unstructured context, without a numeric-format contract.
- **Response format:** the [Transcriptions API reference](https://developers.openai.com/api/reference/resources/audio/subresources/transcriptions/methods/create)
  describes `prompt` as style guidance and recommends matching the audio
  language. `response_format` selects a response representation such as JSON,
  text or subtitles; it does not select decimal separators, digit rendering,
  currency notation or date order.

The file guide's formatting support and the overview's context guidance differ
in emphasis. Together they support testing a concise formatting preference, not
claiming that transcription prompts behave like strict text-editing instructions.
No explicit promise that spoken numbers always become digits, or that a requested
currency/date/decimal/thousands format is enforced, was found in these guides,
the API reference, or targeted official-domain searches. This is a limitation of
the available evidence, not proof that such prompting cannot work.

## Practical interpretation

Converting number words to digits and choosing the resulting number's typography
are separate requirements. Both can be tested through a prompt. A successful
sample establishes behavior for that sample and model, not a universal guarantee
or equivalence between Live and Accurate.

The following are **synthetic candidate prompt fragments**, not official OpenAI
examples or verified results. Use the language of the recording when testing:

```text
Written dictation with numbers represented using digits.
```

```text
Written dictation with numbers represented using digits, spaces between groups
of thousands, and a comma as the decimal separator.
```

Test digits first, then formatting separately. Compare the same audio and model
with no prompt, the existing default, and each candidate. Include integers,
decimals, dates, amounts, identifiers with leading zeros, and phrases where
number words are ordinary prose. Check numeric accuracy as well as typography.

If exact formatting is a product requirement, application-level parsing and
formatting would be a separate implementation decision. It must define locale,
number type and ambiguity handling; a prompt-only experiment does not implement
or validate that processing.

## OpenDictate integration

The application no longer sends a transcription prompt or exposes its editor.
Language and dictionary hints remain separate. Text formatting is configured in
[Post-processing](../apps/macos/README.md#post-processing).
The experiments below describe the earlier request builders at the measured
commit; they do not imply the removed control is still available.

## Empirical results

On 2026-10-19, tested one user-supplied Russian recording (8.832 seconds).
Only aggregate measurements were retained. No audio, transcript text, recognized
numeric values, hashes or raw provider responses were saved in the repository.

The controlled short-prompt series made 54 successful requests: three models ×
six variants × three repeats, in shuffled order. File models received the original
M4A; Live received in-memory 24 kHz mono PCM16 in paced 40 ms chunks, with explicit
commit and turn detection disabled. No word replacement, Responses call or other
post-processing ran. `gpt-transcribe` and Live used `languages: ["ru"]`;
`gpt-4o-transcribe` used automatic language detection, omitting the newer array.

Variants: short neutral Russian context; a Russian digits instruction; an English
digits instruction; space-separated thousands and decimal comma; comma-separated
thousands and decimal point; and exactly two decimal places with `,00` appended
to integers. Each instruction was below the Live prompt-length limit. Examples
inside prompts were explicitly labeled formatting examples, not audio content.

| Model | Digits, Russian instruction | Digits, English instruction | Space-grouped thousands | Comma-grouped thousands | Fixed two decimal places |
| --- | --- | --- | --- | --- | --- |
| `gpt-transcribe` | 0/3 | 0/3 | 0/3 | 0/3 | 0/3 |
| `gpt-live-transcribe` | 0/3 | 0/3 | 0/3 | 0/3 | 0/3 |
| `gpt-4o-transcribe` | 3/3 | 3/3 | 3/3 | 3/3 | 3/3 |

The two default application models kept the number in words, with transcripts
identical to their short-context baseline in every trial. `gpt-4o-transcribe`
rendered digits in only 1/3 neutral-context trials, versus 3/3 with either digits
instruction. Explicit grouping and fixed precision also worked in all three
repeats for that model. Its English digits-only instruction produced grouping
in 2/3 trials, illustrating that digits alone do not specify typography.

An in-memory Russian-cardinal/digit parser found the same recognized numeric
value across model baselines and all formatting variants. This checks internal
value consistency, not accuracy against a manually annotated recording. The
sample exercises a large integer; it does not validate spoken fractional values,
dates, currency units, identifiers, leading zeros or ambiguous number phrases.
Three repeats on one sample are not a reliability guarantee.

A preliminary series also tested the existing full built-in Russian prompt,
with added digits/grouping/precision instructions and with a short complete
replacement. All six variants kept words in all three file-model repeats.
The short Live replacement likewise kept words in three repeats. Full-length
Live variants were rejected before audio was sent, and are not efficacy results.

### Tested prompt examples

These exact short fragments were tested on the sample. They worked for
`gpt-4o-transcribe`, but not for the two current default application models:

```text
Все произнесённые числительные записывай арабскими цифрами, а не словами.
Сохраняй точные значения. Тысячи и миллионы записывай полностью, без сокращений.
```

```text
Все числительные записывай арабскими цифрами. Разделяй тысячи пробелами
по три цифры справа. Десятичный разделитель — запятая. Образцы формата,
не содержание аудио: 12 345,67; 4 000. Сохраняй значения чисел.
```

The second example demonstrates grouping on this integer sample. It does not
establish decimal-comma behavior for a spoken fractional number. A separate
fixed-precision instruction did append `,00` successfully.

### Integration findings

- Live rejected the current built-in Russian prompt with
  `string_above_max_length` on `session.audio.input.transcription.prompt`:
  the server reported a 1,024-character maximum versus 2,624 supplied characters.
  Shorter prompts were accepted. This is an observed API validation result, not
  an inferred documented limit. The editor's 16 KiB storage bound is a different
  limit and cannot ensure endpoint compatibility.
- A `gpt-4o-transcribe` compatibility probe using the application's newer
  `languages[]` request parameter returned HTTP 400; omitting that parameter
  worked. The Android model catalogue also retains only the current transcription
  aliases. Selecting the older model is therefore not a prompt-only change:
  [request builders](../apps/android/app/src/main/java/com/opendictate/app/network/OpenAiTranscriptionClient.kt)
  and the [model catalogue](../apps/android/app/src/main/java/com/opendictate/app/model/ModelCatalog.kt)
  need model-specific handling before claiming support in the application.

For the current default models, the tested prompts are not a dependable way to
require digit rendering or exact typography. Alternatives are deliberate support
for a transcription model that passed the sample, or a separate numeric
normalization stage with value validation and explicit ambiguity handling. Neither
alternative was implemented in this investigation.
