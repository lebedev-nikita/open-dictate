import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import urllib.error

spec = importlib.util.spec_from_file_location("evaluate", Path(__file__).with_name("evaluate-transcription-punctuation.py"))
evaluate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(evaluate)


class EvaluationTest(unittest.TestCase):
    def test_missing_introductory_comma_is_a_failure(self):
        reference = "По идее, дождь закончится, когда выйдет солнце."
        result = evaluate.score("По идее дождь закончится, когда выйдет солнце.", reference)
        self.assertTrue(result["words_match"])
        self.assertFalse(result["commas_correct"])
        self.assertEqual([2], result["missing_commas_after_word"])

    def test_extra_and_duplicate_commas_are_failures(self):
        for candidate in ["По идее,, дождь закончится.", "По идее, дождь, закончится."]:
            result = evaluate.score(candidate, "По идее, дождь закончится.")
            self.assertFalse(result["commas_correct"])
            self.assertTrue(result["extra_commas_after_word"])

    def test_rewritten_words_do_not_pass_with_matching_comma_positions(self):
        result = evaluate.score("По идее, снег закончится.", "По идее, дождь закончится.")
        self.assertFalse(result["words_match"])
        self.assertFalse(result["commas_correct"])
        self.assertIsNone(result["missing_commas_after_word"])

    def test_capitalization_and_optional_yo_do_not_count_as_rewrites(self):
        self.assertTrue(evaluate.score("ПО ИДЕЕ, её ждут.", "По идее, ее ждут.")["commas_correct"])

    def test_dotenv_supports_quotes_and_comments_without_evaluating_shell(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / ".env"
            path.write_text('export TRANSCRIBE_KEY="dummy$(not-a-command)" # comment\n')
            self.assertEqual("dummy$(not-a-command)", evaluate.load_key(path))

    def test_text_key_cannot_replace_transcription_key(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(evaluate.os.environ, {"OPENAI_API_KEY": "text-only"}, clear=True):
            path = Path(directory) / ".env"
            with self.assertRaises(evaluate.EvaluationError):
                evaluate.load_key(path)
            path.write_text('OPENAI_API_KEY="text-only"\n')
            with self.assertRaises(evaluate.EvaluationError):
                evaluate.load_key(path)

    def test_transcription_uses_its_own_environment_key(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(evaluate.os.environ, {
            "OPENAI_API_KEY": "text-only", "TRANSCRIBE_KEY": "transcribe-only",
        }, clear=True):
            self.assertEqual("transcribe-only", evaluate.load_key(Path(directory) / ".env"))

    def test_transcription_ignores_text_endpoint(self):
        class FakeOpener:
            def open(self, request, timeout):
                self.request = request
                return io.BytesIO(b'{"text":"synthetic"}')
        opener = FakeOpener()
        with patch.dict(evaluate.os.environ, {"OPENAI_BASE_URL": "http://127.0.0.1:8317/v1"}):
            evaluate.transcribe(b"synthetic audio", ".wav", "", "transcribe-only", "gpt-transcribe", ["ru"], opener)
        self.assertEqual("https://api.openai.com/v1/audio/transcriptions", opener.request.full_url)
        self.assertEqual("Bearer transcribe-only", opener.request.get_header("Authorization"))

    def test_error_never_discloses_response_body(self):
        class FailingOpener:
            def open(self, *args, **kwargs):
                raise urllib.error.HTTPError("https://api.openai.com", 401, "private text", {}, io.BytesIO(b"dummy secret"))
        with self.assertRaises(evaluate.EvaluationError) as result:
            evaluate.transcribe(b"synthetic audio", ".wav", "context", "dummy-key", "gpt-transcribe", ["ru"], FailingOpener())
        self.assertEqual("OpenAI request failed with HTTP 401", str(result.exception))


if __name__ == "__main__":
    unittest.main()
