# Transcription prompt behavior

Official OpenAI documentation checked on 2026-10-19. This note distinguishes
documented capabilities from hypotheses requiring controlled measurements.
The earlier [number-formatting investigation](transcription-number-formatting.md)
contains the initial single-recording results.

The API measurements below used the transcription defaults from commit
`c8c3bc7a01292956da5de4266451897c27344261`, before the configurable
post-processing implementation.

## Measured conclusion

Keep optional contextual prompting. Both current application models showed a
repeatable lexical effect with `keywords` absent. Do not describe the field as
a reliable text-transformation instruction. The current long generic default
showed no benefit on the tested punctuation sample and exceeds the observed Live
limit. An empty default with optional recording context is a reasonable product
choice; this experiment does not prove that every generic hint is useless on
every recording.

Implement dependable number formatting independently of transcription prompting.
Neither current model followed the tested numeric instructions or transcript-style
examples. The older control model followed several formatting instructions, but
not consistently across repeat series.

## Controlled API measurements

On 2026-10-19, two follow-up series made **205 requests: 204 successful
transcriptions and one rejected Live configuration**. These counts exclude the
earlier number-only investigation. Conditions were repeated three times except
the known-invalid full Live default, attempted once. Trial order was shuffled
with local order seeds 23 and 29; these are not API sampling seeds.

The corpus contained the supplied 8.832-second recording and four synthetic
samples generated locally using macOS speech synthesis. Synthetic sources:

| Sample | Known source text | Voice | Duration |
| --- | --- | --- | --- |
| Russian punctuation | Когда закончишь напиши мне. Я думаю что мы успеем. Анна пришли пожалуйста хлеб молоко и сыр. | Milena | 6.379 s |
| English name | Please send the report to Nivora before tomorrow. | Samantha | 2.738 s |
| Russian name | Мы отправили отчёт в Нивору перед встречей. | Milena | 2.562 s |
| English homophone | I need to check the site. | Samantha | Short synthetic utterance |

Each within-sample comparison used the same audio bytes. Accurate received WAV
for synthetic samples and the original M4A for the supplied recording. Live
received mono PCM16 at 24 kHz, paced in 40 ms chunks, a fresh session per trial,
disabled VAD and an explicit commit. Expected language was `ru` or `en` via
`languages`; the older control omitted the unsupported `languages` field.
No punctuation correction, replacements or other application post-processing ran.

The lexicon tests separated prompt-only context from keyword-only hints.
For example, English prompts described a report sent to the company `Nyvora`
or `Nivora`. Russian prompts described a report sent to `Nyvora` or `«Нивора»`.
These are invented spellings, not user data. The intended lexical spelling is
not uniquely recoverable from audio, which is the reason to supply context.

| Probe | `gpt-transcribe` | `gpt-live-transcribe` |
| --- | --- | --- |
| Russian name with no prompt | Target spelling absent, 3/3 | Target spelling absent, 3/3 |
| Russian context containing «Нивора», no keywords | Target spelling present, 3/3 | Target spelling present, 3/3 |
| Russian context containing Nyvora, no keywords | Cyrillic Нивора, 3/3; exact Latin form 0/3 | Exact Latin Nyvora, 3/3 |
| English context containing Nivora, no keywords | Exact Nivora, 3/3; absent in baseline | Exact Nivora, 3/3; absent in baseline |
| English context containing Nyvora, no keywords | Nivora, 3/3; exact Nyvora 0/3 | Exact Nyvora 1/3, Nivora 1/3, other spelling 1/3 |
| English keyword Nyvora, no prompt | Exact Nyvora 0/3 | Exact Nyvora 3/3 |
| English keyword Nivora, no prompt | Exact Nivora 3/3 | Exact Nivora 3/3 |
| Russian keyword Нивора, no prompt | Target spelling present, 3/3 | Target spelling present, 3/3 |

These results establish an effect of `prompt` independently of `keywords`.
They also show that neither mechanism guarantees the requested spelling. In the
homophone test, context about a building inspection versus an optometrist's work
did not switch `site` to `sight`: both models returned `site` in all three trials
per condition. General topic disambiguation was therefore **not demonstrated**
by that sample; literal name cues provided the positive evidence.

| Style/transformation probe | Accurate | Live |
| --- | --- | --- |
| Short correct-punctuation instruction | Same full transcript as no prompt, 3/3 | Same full transcript as no prompt, 3/3 |
| Existing full Russian default, 2,624 characters | Same full transcript as no prompt, 3/3 | Rejected: `string_above_max_length`, `session.audio.input.transcription.prompt` |
| Lowercase without punctuation | Requested style 0/3; baseline unchanged | Requested style 0/3; baseline unchanged |
| All uppercase | Requested style 0/3; baseline unchanged | Requested style 0/3; baseline unchanged |
| Translate Russian to English | English output 0/3; baseline unchanged | English output 0/3; baseline unchanged |
| Digits instruction, supplied recording | Digits 0/3; baseline unchanged | Digits 0/3; baseline unchanged |
| Digits in transcript-style examples | Digits 0/3; baseline unchanged | Digits 0/3; baseline unchanged |
| Digits in a preceding-transcript example | Digits 0/3; baseline unchanged | Digits 0/3; baseline unchanged |
| Uppercase preceding-transcript example | Uppercase 0/3; baseline unchanged | Uppercase 0/3; same words and comma positions 3/3, exact baseline match 2/3 |

The no-prompt punctuation sample already had all six expected commas in both
newer models, so it has an accuracy ceiling: no measured benefit here does not
establish a universal absence of benefit. The opposite-style probes are more
informative about command following: the requested lowercase, punctuation-free
and uppercase outputs were not produced. One small surface difference with the
uppercase continuation example is not evidence of successful style control.

The numeric example prompts contained synthetic values rather than the user's
recognized number. They tested a transcript-style paragraph and a preceding
transcript paragraph, rather than another imperative. All nine numeric trials
per current model (instruction plus two example styles) retained words, with
zero digit runs and exact equality to that series' no-prompt baseline.

### Older-model control and repeatability

`gpt-4o-transcribe` on the same files followed lowercase/no-punctuation in 3/3
trials, uppercase in 2/3, and produced English output for the translation request
in 3/3. These checks establish visible style/translation effects, not a full
assessment of semantic accuracy. It switched the invented English spelling
between Nyvora and Nivora in 3/3 trials for each respective context.

The repeated digits instruction produced digits in **2/3** trials, with the
remaining response retaining number words. The earlier series had observed
3/3. Even the older model therefore cannot be treated as a guaranteed formatter
on the basis of the initial successful series. It also has different request
field support, so using it would require application compatibility work.

### Measurement limits and privacy

This is a small behavioral experiment, not a production accuracy benchmark.
Four samples use one local TTS voice per language; only the supplied number
recording is human speech. Noise, other accents, mixed-language speech, fillers,
dates, actual spoken decimals and identifiers were not systematically evaluated.
No claim about model architecture follows from the observed behavior. Results
apply to these aliases as served on the test date; backend revisions were not
available. Translation was an instruction-following probe, not a recommendation
to implement translation through this field.

Keys and user transcripts stayed in process memory. Only aggregate measurements,
static prompts and synthetic source text were written to temporary evaluation
artifacts; the user's audio was read from its existing attachment. Temporary
synthetic audio files were removed after decoding. No user transcript or raw
provider payload was written to the repository or logs. Requests used the
existing OpenDictate Keychain API credential and went directly to OpenAI.

## Product recommendation

The product decision supersedes the original recommendations below: remove the
transcription-prompt editor and stop sending application prompts on both
platforms. Language and dictionary hints remain; punctuation and number
formatting use Post-processing. Old local preferences are cleared and old Drive
entries are discarded. The occasional lexical effect measured above does not
justify a separate setting for this product. This does not mean the API prompt
is universally ineffective.

Original recommendations at the measured baseline:

1. Keep the optional shared/synced field, but explain it as **recognition
   context**, with examples of topics and names. Keep exact terms in the existing
   dictionary when possible; keywords are also hints rather than guarantees.
2. Prefer an empty built-in default to the current long instruction block.
   Reset should return to that default, without syncing its literal contents.
   Preserve user overrides. Validate the supported model's limit when editing
   or sending; the current 16 KiB storage bound does not enforce the observed
   1,024-character Live request limit.
3. Keep number recognition and number rendering separate. Recognize speech first,
   then normalize supported numerical expressions into a canonical representation
   that preserves numeric value, sign, fractional precision and identifier zeros;
   render that representation using explicit locale/grouping/precision settings.
   Start with well-defined number types. Leave ambiguous expressions unchanged.
4. For the current models, prefer a local normalizer for deterministic supported
   cases. Broader semantic formatting would need a separately evaluated text
   transformation step. The existing macOS punctuation-only step cannot silently
   acquire numeric rewriting: its validation contract preserves words. Any LLM
   formatter needs numeric-preservation checks, fallback and its own shared
   Android/macOS behavior. There is no universal reliability result in this test.
5. On Live, initially normalize the final result after stop; preserve immediate
   partial delivery. Applying normalization to incomplete number phrases needs
   a separate streaming design. Test both platforms against shared fixtures.

These are recommendations; this investigation changes no application behavior,
model selection, releases or synchronized user settings.

## Documented purpose

- For both `gpt-transcribe` and `gpt-live-transcribe`, `prompt` provides recording
  context, such as topic or setting. `keywords` separately supplies literal terms;
  `languages` supplies expected input languages. OpenAI advises relevant context
  rather than repeating the transcription task. These are recognition hints, not
  required output. [Transcription overview](https://developers.openai.com/api/docs/guides/transcription#improve-transcription-quality)
- The file guide explicitly lists recognition of names, technical terms and
  acronyms; previous-chunk context; punctuation, capitalization and filler-word
  preservation; and writing-system preferences. Its current recommendation names
  `gpt-transcribe`, while also retaining prompt support for `gpt-4o-transcribe`
  and `gpt-4o-mini-transcribe`. It does not promise exact adherence to formatting.
  Its explicit warning about not following general-purpose instructions applies
  to **Whisper**, and must not be generalized to the newer models.
  [File transcription](https://developers.openai.com/api/docs/guides/speech-to-text#prompting)
- The Live guide describes contextual prompting, literal keyword hints and
  language hints, without separately promising punctuation, case or numeric
  formatting control. It rejects an entire session update when a prompt exceeds
  its model-specific length limit. No numeric prompt limit for either newer model
  was found in the reviewed guides; the previously observed Live limit of 1,024
  characters is experimental evidence, not a published universal limit.
  [Realtime transcription](https://developers.openai.com/api/docs/guides/realtime-transcription#add-transcription-context)
- The file API reference describes `prompt` as style/continuation guidance and
  recommends matching the audio language. It identifies `keywords` and `languages`
  as `gpt-transcribe` fields. `response_format` chooses the response container,
  rather than number typography. Transcription returns the input language.
  [Create transcription](https://developers.openai.com/api/reference/resources/audio/subresources/transcriptions/methods/create)
- OpenAI's migration example recommends testing baseline, model-only replacement,
  and context-enhanced requests separately. It calls for exact-name and number
  checks, and checking whether hints insert unspoken words. Translation remains
  a separate supported-model workflow rather than a documented prompt contract.
  [Migration cookbook](https://developers.openai.com/cookbook/examples/migrating_from_whisper_to_gpt_transcribe#9-evaluate-before-and-after)

The model pages independently advertise unstructured context and keyword hints:
[GPT-Transcribe](https://developers.openai.com/api/docs/models/gpt-transcribe),
[GPT-Live-Transcribe](https://developers.openai.com/api/docs/models/gpt-live-transcribe).
These pages establish neither common internals with GPT-4o nor identical prompt
behavior. A model accepting a field, or echoing it in session configuration,
demonstrates transport compatibility rather than measurable output influence.

## What is not established

No reviewed official source says that the two newer aliases ignore all prompts,
or that they reliably execute arbitrary editing, translation or numeric-format
instructions. The file guide's broad formatting statement and the overview's
context advice differ in scope; experiments must resolve behavior for our use.

An unchanged transcript on an easy number recording cannot distinguish an ignored
prompt from a prompt that would matter only under lexical ambiguity. Conversely,
a changed transcript is not automatically an improvement: wrong context can bias
recognition or introduce unspoken content. Repeated baseline variation is needed
before attributing a difference to prompting.

Whether a long default instruction improves Russian punctuation, orthography or
filler handling remains an empirical question. Keeping a context field and
keeping the existing default text are separate product decisions.

## Controlled experiment

Use the same audio bytes for each within-model comparison. Start fresh Live
sessions so earlier turns do not confound results. Keep language hints, keywords,
audio pacing, delay and other parameters constant. Use short accepted prompts
first; classify validation errors separately from successful no-effect trials.

1. **Context without keywords:** use an ambiguous product name or homophone in
   otherwise neutral speech. Compare no prompt, relevant domain context,
   alternative plausible context, and unrelated context. Include an easier clear
   pronunciation and a harder noisy version. Score exact spelling and unspoken
   insertions separately. A relevant-context win without keywords is direct
   evidence that the prompt can influence recognition.
2. **Separate keyword effect:** repeat with prompt absent and the target in
   `keywords`, then with both. A keyword-only win does not prove prompt efficacy.
3. **Style apart from recognition:** use fixed audio containing pauses, fillers,
   proper nouns and Russian orthographic choices. Compare compact explicit style
   requests with transcript-style examples and the current default. Measure
   punctuation, case, filler retention and spelling independently from words.
4. **Transformation:** test digits versus words, grouping, decimal precision,
   uppercase/lowercase, and translation independently. Include integers,
   fractions, identifiers and ambiguous phrases. A successful text transformation
   is distinct from improved acoustic recognition.
5. **Repeat and control:** shuffle conditions and repeat at least three times;
   compare against multiple no-prompt baselines. Use `gpt-4o-transcribe` as a
   separate control with its supported request fields. Do not treat cross-model
   differences as causal evidence for a specific prompt.

Synthetic audio is useful for a first controlled comparison but does not measure
human-dictation reliability. Use manually checked real recordings before making
product guarantees. Retain aggregate feature counts and pass/fail measurements;
keep private audio, transcripts, credentials and raw provider payloads out of
repository files and logs.

## Decision criteria

- Preserve contextual prompting if it helps domain vocabulary or disambiguation,
  even if numeric instructions fail. The UI should describe the demonstrated
  capability rather than promising arbitrary text editing.
- Replace or remove the built-in instruction only after comparing its individual
  components with no prompt. A universal task reminder may add length without
  providing recording-specific information.
- Implement number formatting through a separately validated normalization step
  when exact output is required and current-model prompts fail. Define locale,
  number types and ambiguity handling; protect numeric value and leading zeros.
- A model that follows style instructions may reduce the need for normalization,
  but requires model-specific API compatibility and representative accuracy,
  latency and formatting evaluation before becoming an application default.
