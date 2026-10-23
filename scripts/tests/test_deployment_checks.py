import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('deployment', Path(__file__).resolve().parents[1] / 'check-deployment.py')
DEPLOYMENT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(DEPLOYMENT)


class DeploymentChecks(unittest.TestCase):
    def test_only_local_root_http_urls_are_allowed(self):
        for value in ('https://example.com', 'http://127.0.0.1@evil.example', 'http://127.0.0.1/path',
                      'http://localhost/?token=hidden', 'http://localhost/#fragment', 'file:///tmp/file'):
            with self.subTest(value=value), self.assertRaises(RuntimeError):
                DEPLOYMENT.base_url(value)
        self.assertEqual(DEPLOYMENT.base_url('http://127.0.0.1:8080/'), 'http://127.0.0.1:8080')

    def test_mismatched_deployment_refuses_faults_before_any_mutation(self):
        with patch('sys.argv', ['check', '--faults', '--restart', '--base-url', 'http://127.0.0.1:18081']), \
                patch.object(DEPLOYMENT, 'compose', return_value='127.0.0.1:8080') as compose:
            with self.assertRaises(RuntimeError):
                DEPLOYMENT.main()
            compose.assert_called_once_with('port', 'frontend', '8080')

    def test_output_cannot_overwrite_source_files(self):
        for path in ('README.md', 'backend/target/../../README.md', 'backend/target/report.txt'):
            with self.subTest(path=path), patch('sys.argv', ['check', '--output', path]), \
                    patch.object(DEPLOYMENT, 'compose') as compose:
                with self.assertRaises(RuntimeError):
                    DEPLOYMENT.main()
                compose.assert_not_called()

    def test_invalid_project_is_rejected_before_docker(self):
        for project in ('../investlens', '--project-name', 'UPPERCASE'):
            with self.subTest(project=project), patch('sys.argv', ['check', '--project=' + project]), \
                    patch.object(DEPLOYMENT, 'compose') as compose:
                with self.assertRaises(RuntimeError):
                    DEPLOYMENT.main()
                compose.assert_not_called()

    def test_invalid_label_is_rejected_before_docker(self):
        with patch('sys.argv', ['check', '--label', '../secret']), patch.object(DEPLOYMENT, 'compose') as compose:
            with self.assertRaises(RuntimeError):
                DEPLOYMENT.main()
            compose.assert_not_called()


if __name__ == '__main__':
    unittest.main()
