import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location("prewarm", Path(__file__).with_name("evaluate-prewarm.py"))
prewarm = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prewarm)
analysis_spec = importlib.util.spec_from_file_location("prewarm_analysis", Path(__file__).with_name("analyze-prewarm-study.py"))
analysis = importlib.util.module_from_spec(analysis_spec)
analysis_spec.loader.exec_module(analysis)
transport_spec = importlib.util.spec_from_file_location("prewarm_transport", Path(__file__).with_name("evaluate-prewarm-transport.py"))
transport = importlib.util.module_from_spec(transport_spec)
transport_spec.loader.exec_module(transport)
race_spec = importlib.util.spec_from_file_location("prewarm_race", Path(__file__).with_name("evaluate-prewarm-race.py"))
race = importlib.util.module_from_spec(race_spec)
race_spec.loader.exec_module(race)


def stream(events):
    return io.BytesIO(b"".join(("data: " + json.dumps(event) + "\r\n\r\n").encode() for event in events))


def completed(text=None, usage=None, output=None):
    if output is None:
        output = [] if text is None else [{"type": "message", "content": [{"type": "output_text", "text": text}]}]
    return {"type": "response.completed", "response": {"status": "completed", "output": output, "usage": usage or {}}}


class StreamTests(unittest.TestCase):
    def test_ttft_ignores_metadata_and_empty_deltas(self):
        payload = json.dumps({"transformed_text": "Встреча 2026 года."})
        events = [{"type": "response.created"}, {"type": "response.output_text.delta", "delta": ""},
                  {"type": "response.output_text.delta", "delta": "{"},
                  {"type": "response.output_text.delta", "delta": "another piece"}, completed(payload)]
        times = iter([1.5, 1.8, 2.0])
        result = prewarm.consume(stream(events), 1, ("2026",), clock=lambda: next(times))
        self.assertEqual(result["ttft_ms"], 500)
        self.assertEqual(result["completion_ms"], 800)
        self.assertTrue(result["valid_json"])
        self.assertTrue(result["content_check"])
        self.assertEqual(result["text_deltas"], 2)
        self.assertNotIn("text", result)
        self.assertNotIn("Встреча", json.dumps(result, ensure_ascii=False))

    def test_finished_item_fallback_requires_terminal_completion(self):
        item = {"type": "response.output_item.done", "output_index": 0, "item": {
            "type": "message", "content": [{"type": "output_text", "text": '{"transformed_text":"ok"}'}]}}
        partial = prewarm.consume(stream([item]), 0, clock=lambda: 1)
        self.assertEqual(partial["status"], "missing_terminal")
        self.assertFalse(partial["valid_json"])
        final = prewarm.consume(stream([item, completed()]), 0, clock=lambda: 1)
        self.assertTrue(final["valid_json"])
        self.assertIsNone(final["ttft_ms"])

    def test_terminal_snapshot_overrides_item_and_late_error_invalidates(self):
        item = {"type": "response.output_item.done", "output_index": 0, "item": {
            "type": "message", "content": [{"type": "output_text", "text": '{"transformed_text":"stale"}'}]}}
        events = [item, completed('{"transformed_text":"authoritative"}')]
        result = prewarm.consume(stream(events), 0, ("authoritative",), clock=lambda: 1)
        self.assertTrue(result["content_check"])
        failed = prewarm.consume(stream(events + [{"type": "error"}]), 0, clock=lambda: 1)
        self.assertFalse(failed["valid_json"])

    def test_prewarm_usage_without_generation(self):
        usage = {"input_tokens": 2400, "input_tokens_details": {"cached_tokens": 0, "cache_write_tokens": 2390}, "output_tokens": 0}
        result = prewarm.consume(stream([completed(usage=usage)]), 0, clock=lambda: 1)
        self.assertEqual(result["cache_write_tokens"], 2390)
        self.assertEqual(result["output_chars"], 0)
        self.assertIsNone(result["ttft_ms"])
        self.assertFalse(result["valid_json"])

    def test_stream_cut_off_after_first_text_is_not_success(self):
        result = prewarm.consume(stream([{"type": "response.output_text.delta", "delta": "{"}]),
                                 0, clock=lambda: 1)
        self.assertEqual(result["ttft_ms"], 1000)
        self.assertIsNone(result["completion_ms"])
        self.assertEqual(result["status"], "missing_terminal")
        self.assertFalse(result["valid_json"])

    def test_multiline_sse_and_comments(self):
        raw = io.BytesIO(b': ping\n\ndata: {"type":\ndata: "example"}\n\ndata: [DONE]\n\n')
        self.assertEqual(list(prewarm.sse_events(raw)), ['{"type":\n"example"}', '[DONE]'])

    def test_invalid_json_and_numeric_change_are_reported(self):
        for payload in ("bad json", '{"transformed_text":"400 руб."}', '{"transformed_text":""}'):
            result = prewarm.consume(stream([completed(payload)]), 0, ("432",), clock=lambda: 1)
            self.assertFalse(result["content_check"])


class CredentialTests(unittest.TestCase):
    def test_selected_env_file_missing_key_never_uses_another_key_or_environment(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / ".env"
            path.write_text("TRANSCRIBE_KEY=transcription-secret\n")
            with patch.dict(prewarm.os.environ, {"OPENAI_API_KEY": "other-secret"}):
                with self.assertRaises(prewarm.StudyError):
                    prewarm.load_key(path, "OPENAI_API_KEY")

    def test_transcription_override_is_explicit_and_one_run_only(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / ".env"
            path.write_text("export TRANSCRIBE_KEY='private-value' # comment\n")
            with self.assertRaises(prewarm.StudyError):
                prewarm.load_key(path, "TRANSCRIBE_KEY")
            self.assertEqual(prewarm.load_key(path, "TRANSCRIBE_KEY", True), "private-value")
            self.assertNotIn("private-value", path.name)

    def test_http_error_never_retains_body_or_credentials(self):
        connection = prewarm.Connection("private-key")
        class FakeResponse:
            status = 401
            def read(self):
                return b"private response body"
        class FakeConnection:
            def request(self, *args, **kwargs):
                pass
            def getresponse(self):
                return FakeResponse()
        connection.connection = FakeConnection()
        result = connection.request({"synthetic": "private source"})
        self.assertEqual(result["http_status"], 401)
        self.assertNotIn("private", json.dumps(result))


class RequestTests(unittest.TestCase):
    def test_prewarm_has_exact_inference_prefix_but_no_source(self):
        template = {"model": "gpt-6-luna", "input": json.dumps({"instruction": "stable rules", "source_text": ""}),
                    "instructions": "system", "reasoning": {"effort": "none"}, "store": False}
        prep = prewarm.request_body(template, "partition", prewarm=True)
        inference = prewarm.request_body(template, "partition", "private synthetic suffix")
        self.assertEqual(prep["input"], inference["input"][:1])
        self.assertNotIn("private synthetic suffix", json.dumps(prep))
        self.assertTrue(prep["prompt_cache_options"]["prewarm"])
        self.assertNotIn("prewarm", inference["prompt_cache_options"])
        self.assertEqual(prep["reasoning"], {"effort": "none"})
        self.assertIsInstance(template["input"], str)

    def test_paired_analysis_matches_blocks_and_keeps_invalid_outputs(self):
        rows = [dict(phase="paired", scope="ru_en", block=block, arm=arm, ttft_ms=value, valid_json=False)
                for block, arm, value in [(2,"cold",1000), (1,"prewarmed",1100), (1,"cold",1500), (2,"prewarmed",600)]]
        result = prewarm.paired_summary(rows, "paired", "ru_en", "cold", "prewarmed", "ttft_ms", 100)
        self.assertEqual(result["n"], 2)
        self.assertEqual(result["paired_median_saving_ms"], 400)
        self.assertEqual(result["ci95_ms"], [400, 400])

    def test_exact_median_interval_and_missing_pair_accounting(self):
        rows = []
        for block in range(20):
            rows.extend([dict(phase="paired", scope="ru_en", block=block, arm="cold", ttft_ms=1000+block+1),
                         dict(phase="paired", scope="ru_en", block=block, arm="prewarmed", ttft_ms=1000)])
        rows.append(dict(phase="paired", scope="ru_en", block=20, arm="cold", ttft_ms=None))
        result = prewarm.paired_summary(rows, "paired", "ru_en", "cold", "prewarmed", "ttft_ms", 100)
        self.assertEqual(result["attempted_pairs"], 21)
        self.assertEqual(result["n"], 20)
        self.assertEqual(result["sign_median_ci95_ms"], [6, 15])


class DecisionTests(unittest.TestCase):
    def test_practical_benefit_requires_both_lower_bounds(self):
        self.assertEqual(analysis.decision({"n":128,"ci95_ms":[201,400],"fraction_ci95":[.101,.3]}),
                         "practical_threshold_supported")
        self.assertEqual(analysis.decision({"n":128,"ci95_ms":[50,400],"fraction_ci95":[.05,.3]}),
                         "benefit_supported_magnitude_uncertain")
        self.assertEqual(analysis.decision({"n":128,"ci95_ms":[-50,400],"fraction_ci95":[-.05,.3]}),
                         "inconclusive")

    def test_upper_bound_can_exclude_only_the_specified_threshold(self):
        self.assertEqual(analysis.decision({"n":128,"ci95_ms":[-50,150],"fraction_ci95":[-.05,.3]}),
                         "specified_practical_threshold_not_supported_within_interval")


class TransportProtocolTests(unittest.TestCase):
    def test_each_block_uses_three_fresh_connections_and_balanced_preparation(self):
        instances=[]
        class FakeConnection:
            def __init__(self, key):
                self.calls=[]
                instances.append(self)
            def warm_transport(self):
                self.calls.append("get")
            def request(self, body, checks=()):
                prep=body.get("prompt_cache_options",{}).get("prewarm",False)
                self.calls.append("prep" if prep else "infer")
                return {"status":"completed","ttft_ms":None if prep else 10,"completion_ms":20,
                        "valid_json":not prep,"cache_read_tokens":2000,"output_tokens":0 if prep else 20}
            def close(self):
                self.calls.append("close")
        template={"model":"gpt-6-luna","input":json.dumps({"instruction":"stable rules"})}
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(transport.prewarm,"Connection",FakeConnection), patch.object(transport.prewarm,"paired_summary",return_value={"n":24}), patch("builtins.print"):
                transport.run(Path(directory)/"run","unused-private-key",{"ru_en":template})
            rows=[json.loads(line) for line in (Path(directory)/"run/measurements.jsonl").read_text().splitlines()]
        self.assertEqual(len(instances),73)
        self.assertEqual(sum(c.calls.count("get") for c in instances),25)
        self.assertEqual(sum(c.calls.count("prep") for c in instances),24)
        self.assertEqual(sum(c.calls.count("infer") for c in instances),73)
        self.assertEqual(len(rows),121)
        self.assertEqual(sum(r["phase"]=="transport" for r in rows),72)
        for connection in instances[1:]:
            self.assertIn(connection.calls,[["infer","close"],["get","infer","close"],["prep","infer","close"]])

    def test_replication_balances_all_permutations_and_keeps_prefix_controls(self):
        instances=[]
        class FakeConnection:
            def __init__(self,key):
                self.bodies=[]
                instances.append(self)
            def warm_transport(self):
                pass
            def request(self,body,checks=()):
                self.bodies.append(body)
                prep=body.get("prompt_cache_options",{}).get("prewarm",False)
                return {"status":"completed","ttft_ms":None if prep else 10,"completion_ms":20,
                        "valid_json":not prep,"cache_read_tokens":2000,"output_tokens":0 if prep else 20}
            def close(self):
                pass
        template={"model":"gpt-6-luna","input":json.dumps({"instruction":"stable rules"})}
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(race.prewarm,"Connection",FakeConnection), patch.object(race.prewarm,"paired_summary",return_value={"n":48}), patch("builtins.print"):
                race.run(Path(directory)/"run","unused-private-key",{"ru_en":template})
            rows=[json.loads(line) for line in (Path(directory)/"run/measurements.jsonl").read_text().splitlines()]
        self.assertEqual(len(rows),336)
        self.assertEqual(len(instances[0].bodies),192)
        self.assertEqual(len(instances[1].bodies),144)
        orders=[]
        for block in range(48):
            arms=[r["arm"] for r in rows if r["block"]==block and not r["arm"].startswith("preparation")]
            orders.append(tuple(arms))
        from collections import Counter
        self.assertEqual(set(Counter(orders).values()),{2})
        self.assertEqual(len(set(orders)),24)
        inference_prefixes=[body["input"][0] for body in instances[0].bodies]
        prepared_prefixes=[body["input"][0] for body in instances[1].bodies]
        self.assertEqual(sum(prefix in inference_prefixes for prefix in prepared_prefixes),96)


if __name__ == "__main__":
    unittest.main()
