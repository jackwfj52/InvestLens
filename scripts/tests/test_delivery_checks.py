import importlib.util
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('delivery', Path(__file__).resolve().parents[1] / 'check-delivery.py')
DELIVERY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(DELIVERY)


class DeliveryChecks(unittest.TestCase):
    def test_detected_credential_is_not_in_the_exception(self):
        sentinel = b'only-a-local-test-sentinel'
        with self.assertRaises(RuntimeError) as failure:
            DELIVERY.no_secrets(b'prefix ' + sentinel + b' suffix', [sentinel])
        self.assertNotIn(sentinel.decode(), str(failure.exception))
        DELIVERY.no_secrets(b'ordinary output', [sentinel])

    def test_only_named_current_credentials_are_collected(self):
        with TemporaryDirectory() as directory, patch.object(DELIVERY, 'ROOT', Path(directory)), patch.dict('os.environ', {}, clear=True):
            (Path(directory) / '.env').write_text('LLM_API_KEY="only-a-local-test-sentinel"\nOTHER=not-a-credential-value\nMYSQL_PASSWORD=short\n', encoding='utf-8')
            self.assertEqual(DELIVERY.credentials(), [b'only-a-local-test-sentinel'])

    def test_network_paths_are_rejected_before_http(self):
        with patch.object(DELIVERY.HTTP, 'open') as outgoing:
            with self.assertRaises(RuntimeError):
                DELIVERY.read('http://127.0.0.1:8080', '//external.example/path', [])
            outgoing.assert_not_called()


if __name__ == '__main__':
    unittest.main()
