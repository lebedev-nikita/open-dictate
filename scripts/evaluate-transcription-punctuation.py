#!/usr/bin/env python3
"""Compare transcription prompts with real API requests; print metrics, never credentials or transcripts.

Uses only the Python standard library. Bootstrap writes one transcript to a private
file for manual punctuation annotation; keep that reference outside the repository.
"""

import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import random
import re
import shlex
import statistics
import sys
import time
import unicodedata
import urllib.error
import urllib.request
import uuid


class EvaluationError(Exception):
    pass


def load_key(env_file):
    if env_file.is_file():
        for line in env_file.read_text().splitlines():
            name, separator, value = line.strip().partition("=")
            if separator and name.removeprefix("export ").strip() == "OPENAI_API_KEY":
                values = shlex.split(value, comments=True)
                if len(values) == 1 and values[0]:
                    return values[0]
        raise EvaluationError("OPENAI_API_KEY is missing from the supplied env file")
    key = os.environ.get("OPENAI_API_KEY")
    if not key:
        raise EvaluationError("Configure OPENAI_API_KEY in the env file or environment")
    return key


def parse_text(text):
    tokens = list(re.finditer(r"[^\W_]+(?:['’\-][^\W_]+)*", text, re.UNICODE))
    words = tuple(unicodedata.normalize("NFC", token.group()).casefold()
                  .replace("ё", "е").replace("’", "'") for token in tokens)
    commas = [0] * text[:tokens[0].start() if tokens else len(text)].count(",")
    for index, token in enumerate(tokens):
        suffix = text[token.end():tokens[index + 1].start() if index + 1 < len(tokens) else len(text)]
        commas.extend([index + 1] * suffix.count(","))
    return words, Counter(commas)


def score(text, reference):
    words, commas = parse_text(text)
    expected_words, expected_commas = parse_text(reference)
    words_match = words == expected_words
    return {
        "words_match": words_match,
        "commas_correct": words_match and commas == expected_commas,
        "missing_commas_after_word": sorted((expected_commas - commas).elements()) if words_match else None,
        "extra_commas_after_word": sorted((commas - expected_commas).elements()) if words_match else None,
    }


class NoRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        return None


def transcribe(audio, suffix, prompt, key, model, languages, opener):
    boundary = uuid.uuid4().hex
    body = bytearray()
    fields = [("model", model), *[("languages[]", language) for language in languages]]
    if prompt:
        fields.append(("prompt", prompt))
    for name, value in fields:
        body.extend(f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n{value}\r\n'.encode())
    mime_type = {".wav": "audio/wav", ".m4a": "audio/mp4", ".mp3": "audio/mpeg"}.get(suffix, "application/octet-stream")
    body.extend(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="dictation{suffix}"\r\nContent-Type: {mime_type}\r\n\r\n'.encode())
    body.extend(audio)
    body.extend(f"\r\n--{boundary}--\r\n".encode())
    request = urllib.request.Request("https://api.openai.com/v1/audio/transcriptions", data=bytes(body),
                                     headers={"Authorization": "Bearer " + key,
                                              "Content-Type": "multipart/form-data; boundary=" + boundary})
    started = time.monotonic()
    try:
        with opener.open(request, timeout=60) as response:
            payload = json.load(response)
        text = payload.get("text")
        if not isinstance(text, str) or not text.strip():
            raise EvaluationError("API returned an empty transcription")
        return text.strip(), round(time.monotonic() - started, 3)
    except urllib.error.HTTPError as error:
        error.close()
        raise EvaluationError(f"OpenAI request failed with HTTP {error.code}") from None


def private_write(path, text):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w") as output:
        output.write(text)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--audio", type=Path, required=True)
    parser.add_argument("--env-file", type=Path, default=Path(".env"))
    parser.add_argument("--model", default="gpt-transcribe")
    parser.add_argument("--languages", nargs="+", default=["ru", "en"])
    parser.add_argument("--prompts-file", type=Path, default=Path(__file__).with_name("transcription-prompts.json"))
    parser.add_argument("--reference-file", type=Path)
    parser.add_argument("--reference-template", type=Path, help="Bootstrap: save one transcript for manual annotation and exit")
    parser.add_argument("--bootstrap-prompt", default="rc2")
    parser.add_argument("--repeats", type=int, default=5)
    parser.add_argument("--order-seed", type=int, default=42, help="Shuffle trial order; does not set an API seed")
    parser.add_argument("--confirm", help="Prompt ID to recheck in an independent final series")
    parser.add_argument("--confirmation-repeats", type=int, default=10)
    parser.add_argument("--report", type=Path, help="Save metrics without transcripts")
    args = parser.parse_args()
    if args.repeats < 1 or args.confirmation_repeats < 1:
        parser.error("Repeat counts must be positive")
    if bool(args.reference_file) == bool(args.reference_template):
        parser.error("Supply exactly one of --reference-file or --reference-template")
    prompts = json.loads(args.prompts_file.read_text())
    if not isinstance(prompts, dict) or not prompts or not all(isinstance(value, str) for value in prompts.values()):
        raise EvaluationError("Prompts must be a nonempty JSON object mapping IDs to strings")
    for selected in [args.bootstrap_prompt, args.confirm]:
        if selected is not None and selected not in prompts:
            raise EvaluationError("Selected prompt ID is missing")
    key = load_key(args.env_file)
    audio = args.audio.read_bytes()
    opener = urllib.request.build_opener(NoRedirects())
    if args.reference_template:
        if args.reference_template.exists():
            raise EvaluationError("Reference template already exists; choose a new path")
        text, elapsed = transcribe(audio, args.audio.suffix.lower(), prompts[args.bootstrap_prompt], key,
                                   args.model, args.languages, opener)
        private_write(args.reference_template, text)
        print(json.dumps({"reference_template_created": True, "seconds": elapsed}))
        return
    reference = args.reference_file.read_text().strip()
    if not parse_text(reference)[0]:
        raise EvaluationError("Reference must contain words")
    results = []
    trial_order = []
    order = random.Random(args.order_seed)
    for _ in range(args.repeats):
        round_order = list(prompts)
        order.shuffle(round_order)
        trial_order.extend((variant, "comparison") for variant in round_order)
    if args.confirm:
        trial_order.extend((args.confirm, "confirmation") for _ in range(args.confirmation_repeats))
    for variant, phase in trial_order:
        text, elapsed = transcribe(audio, args.audio.suffix.lower(), prompts[variant], key,
                                   args.model, args.languages, opener)
        result = {"trial": len(results) + 1, "variant": variant, "phase": phase,
                  "seconds": elapsed, **score(text, reference)}
        results.append(result)
        print(json.dumps(result), flush=True)
    summaries = []
    for variant, phase in dict.fromkeys((result["variant"], result["phase"]) for result in results):
        trials = [result for result in results if result["variant"] == variant and result["phase"] == phase]
        summaries.append({"variant": variant, "phase": phase, "trials": len(trials),
                          "correct_commas": sum(result["commas_correct"] for result in trials),
                          "words_match": sum(result["words_match"] for result in trials),
                          "median_seconds": round(statistics.median(result["seconds"] for result in trials), 3),
                          "min_seconds": min(result["seconds"] for result in trials),
                          "max_seconds": max(result["seconds"] for result in trials)})
    report = {"model": args.model, "languages": args.languages, "audio_sha256": hashlib.sha256(audio).hexdigest(),
              "reference_sha256": hashlib.sha256(reference.encode()).hexdigest(),
              "order_seed": args.order_seed,
              "prompt_sha256": {variant: hashlib.sha256(prompt.encode()).hexdigest() for variant, prompt in prompts.items()},
              "summaries": summaries, "trials": results}
    print(json.dumps({"summaries": summaries}), flush=True)
    if args.report:
        private_write(args.report, json.dumps(report, indent=2) + "\n")


if __name__ == "__main__":
    try:
        main()
    except EvaluationError as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
    except Exception as error:
        print(f"Evaluation stopped ({type(error).__name__}); sensitive error details suppressed", file=sys.stderr)
        sys.exit(1)
