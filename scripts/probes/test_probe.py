"""Offline contract/security checks for the probe, not market-data acceptance."""
import argparse
from copy import deepcopy
from datetime import date
from email.message import Message
import io
import json
import os
import unittest
from unittest.mock import patch
from urllib.error import HTTPError, URLError
from urllib.request import ProxyHandler

import probe

START, END = date(2026, 6, 24), date(2026, 10, 22)


class Response(io.BytesIO):
    def __init__(self, body=b'{}', code=200, headers=None):
        super().__init__(body)
        self.code = code
        self.headers = headers or Message()


class Opener:
    def __init__(self, response=None, error=None):
        self.response, self.error, self.calls = response, error, 0

    def open(self, request, timeout):
        self.calls += 1
        if self.error:
            raise self.error
        return self.response


class ProbeTests(unittest.TestCase):
    def test_proxy_discovery_is_disabled(self):
        fake = Opener(Response())
        with patch.dict(os.environ, {'HTTPS_PROXY': 'http://unused.invalid:9'}), patch.object(probe, 'build_opener', return_value=fake) as build:
            summary, _ = probe.transport('https://example.invalid/data', timeout=1)
        handlers = build.call_args.args
        self.assertTrue(any(isinstance(h, ProxyHandler) and h.proxies == {} for h in handlers))
        self.assertEqual(summary['httpStatus'], 200)

    def test_http_errors_are_single_attempt_and_never_echo_body(self):
        for code, expected in [(401, 'AUTHENTICATION_FAILED'), (403, 'ACCESS_DENIED'), (429, 'RATE_LIMITED'), (500, 'UPSTREAM_HTTP_ERROR')]:
            with self.subTest(code=code):
                headers = Message()
                headers['Retry-After'] = '7'
                error = HTTPError('https://example.invalid?apikey=SECRET', code, 'SECRET', headers, io.BytesIO(b'SECRET'))
                fake = Opener(error=error)
                summary, _ = probe.transport('https://example.invalid', timeout=1, opener=fake)
                self.assertEqual(fake.calls, 1)
                self.assertEqual(summary['failureCode'], expected)
                self.assertEqual(summary['retryAfter'], {'seconds': 7})
                self.assertNotIn('SECRET', json.dumps(summary))

    def test_redirects_are_reported_without_following_or_logging_query(self):
        headers = Message()
        headers['Location'] = 'https://verify.example.invalid/?token=SECRET'
        fake = Opener(Response(code=302, headers=headers))
        summary, _ = probe.transport('https://example.invalid', timeout=1, opener=fake)
        self.assertEqual(fake.calls, 1)
        self.assertEqual(summary['failureCode'], 'REDIRECT_NOT_FOLLOWED')
        self.assertEqual(summary['redirectHost'], 'verify.example.invalid')
        self.assertNotIn('SECRET', json.dumps(summary))

    def test_network_errors_are_sanitized(self):
        for error, expected in [(TimeoutError('SECRET'), 'TIMEOUT'), (URLError('SECRET'), 'NETWORK_ERROR')]:
            summary, raw = probe.transport('https://example.invalid', timeout=1, opener=Opener(error=error))
            self.assertEqual(summary['failureCode'], expected)
            self.assertEqual(raw, b'')
            self.assertNotIn('SECRET', json.dumps(summary))

    def test_response_size_is_bounded(self):
        with patch.object(probe, 'MAX_BYTES', 4):
            summary, _ = probe.transport('https://example.invalid', timeout=1, opener=Opener(Response(b'12345')))
        self.assertEqual(summary['failureCode'], 'RESPONSE_TOO_LARGE')

    def test_unsafe_endpoints_rejected(self):
        for url in ('http://remote.invalid', 'https://name:SECRET@example.invalid'):
            with self.subTest(url=url), self.assertRaises(probe.ProbeError):
                probe.transport(url, timeout=1)

    def test_duplicate_json_keys_and_nonfinite_values_rejected(self):
        for raw in ('{"ok":true,"ok":false}', '{"value":NaN}', '<html>'):
            with self.assertRaises(probe.ProbeError):
                probe.strict_json(raw)

    def test_bar_quality_rejections(self):
        row = {'date': '2026-10-22', 'open': '10', 'high': '12', 'low': '9', 'close': '11'}
        self.assertEqual(probe.validate_bars([row], START, END)['sampleCount'], 1)
        invalid = [[], [row, row], [{**row, 'close': None}], [{**row, 'close': '0'}],
                   [{**row, 'close': 'NaN'}], [{**row, 'low': '13'}], [{**row, 'date': '2026-10-23'}]]
        for rows in invalid:
            with self.assertRaises(probe.ProbeError):
                probe.validate_bars(rows, START, END)

    def test_twelve_body_error_on_http_200(self):
        with self.assertRaisesRegex(probe.ProbeError, 'RATE_LIMITED'):
            probe.parse_twelve(b'{"status":"error","code":429,"message":"SECRET"}', 'AAPL', START, END)

    def test_sina_never_executes_remote_script(self):
        for raw in (b'process.exit(0)', b'var EVIL="AAAA";', b'var KLC_KL_usAAPL="AAAA"; evil();'):
            with self.assertRaises(probe.ProbeError), patch.object(probe.subprocess, 'run') as run:
                probe.parse_sina(raw, 'AAPL', START, END)
            run.assert_not_called()

    def test_baidu_structured_bars_and_closed_window(self):
        body = {'ResultCode': 0, 'Result': {'basicinfos': {'code': 'AAPL'}, 'newMarketData': {
            'keys': ['time', 'open', 'high', 'low', 'close'],
            'marketData': '2026-10-22,10,12,9,11;2026-10-23,10,12,9,11'}}}
        result = probe.parse_baidu(json.dumps(body), 'AAPL', START, END)
        self.assertEqual(result['sampleCount'], 1)
        self.assertEqual(result['excludedOutsideWindow'], 1)
        for mutation in (lambda b: b.update(ResultCode=403),
                         lambda b: b['Result']['basicinfos'].update(code='OTHER'),
                         lambda b: b['Result']['newMarketData']['keys'].append('time')):
            bad = deepcopy(body)
            mutation(bad)
            with self.assertRaises(probe.ProbeError):
                probe.parse_baidu(json.dumps(bad), 'AAPL', START, END)

    def test_llm_json_and_usage(self):
        args = argparse.Namespace(key_env='TEST_API_KEY', base_url='https://example.invalid', model='test-model', disable_thinking=True, timeout=1)
        body = {'model': 'test-model', 'choices': [{'finish_reason': 'stop', 'message': {'content': '{"probe":"investlens","ok":true}'}}],
                'usage': {'prompt_tokens': 10, 'completion_tokens': 12, 'total_tokens': 22}}
        summary = {'failureCode': None, 'attempts': 1}
        with patch.dict(os.environ, {'TEST_API_KEY': 'SECRET'}), patch.object(probe, 'transport', return_value=(summary, json.dumps(body))) as call:
            result = probe.llm_probe(args)
        self.assertEqual(result['technicalStatus'], 'PASSED')
        self.assertEqual(result['usage']['total_tokens'], 22)
        self.assertFalse(result['serverStrictSchemaGuaranteed'])
        self.assertEqual(call.call_args.kwargs['payload']['thinking'], {'type': 'disabled'})

    def test_llm_invalid_outputs_are_blocked(self):
        args = argparse.Namespace(key_env='TEST_API_KEY', base_url='https://example.invalid', model='test-model', disable_thinking=False, timeout=1)
        cases = [('length', '{"probe":"investlens","ok":true}'), ('stop', ''),
                 ('stop', '```json\n{}\n```'), ('stop', '{"probe":"investlens","ok":1}'),
                 ('stop', '{"probe":"investlens","ok":true,"other":1}')]
        for finish, content in cases:
            body = {'choices': [{'finish_reason': finish, 'message': {'content': content}}]}
            with patch.dict(os.environ, {'TEST_API_KEY': 'SECRET'}), patch.object(probe, 'transport', return_value=({'failureCode': None}, json.dumps(body))):
                self.assertEqual(probe.llm_probe(args)['technicalStatus'], 'BLOCKED')

    def test_redaction_preserves_json_structure_and_types(self):
        with patch.dict(os.environ, {'TEST_API_KEY': 'secret"\\value', 'TEST_TOKEN': 'true'}):
            output = probe.serialize_report({'message': 'secret"\\value true', 'ok': True, 'count': 200})
        parsed = json.loads(output)
        self.assertEqual(parsed['message'], '[REDACTED] [REDACTED]')
        self.assertIs(parsed['ok'], True)
        self.assertEqual(parsed['count'], 200)

    def test_env_file_never_evaluates_shell_or_overwrites_environment(self):
        with patch.object(probe.Path, 'read_text', return_value='TEST_API_KEY=file-value\nTEST_VALUE="$(not-a-command)"\n'):
            with patch.dict(os.environ, {'TEST_API_KEY': 'environment-value'}, clear=True):
                probe.load_env('ignored-test.env')
                self.assertEqual(os.environ['TEST_API_KEY'], 'environment-value')
                self.assertEqual(os.environ['TEST_VALUE'], '$(not-a-command)')

    def test_missing_market_key_makes_zero_requests(self):
        args = argparse.Namespace(provider='twelvedata', end_date='2026-10-22', stock='AAPL', gold='GLD', silver='SLV')
        with patch.dict(os.environ, {}, clear=True), patch.object(probe, 'transport') as call:
            result = probe.market_probe(args)
        call.assert_not_called()
        self.assertEqual(result['technicalStatus'], 'BLOCKED')
        self.assertTrue(all(r['attempts'] == 0 for r in result['results']))

    def test_crypto_requires_closed_utc_daily_bars(self):
        opening = 1792627200000  # 2026-10-22T00:00:00Z
        closing = opening + 86_400_000 - 1
        row = [opening, '10', '12', '9', '11', '1', closing, '1', 1, '1', '1', '0']
        result = probe.parse_crypto_bars(json.dumps([row]), START, END, closing)
        self.assertEqual(result['sampleCount'], 1)
        row[6] += 1
        with self.assertRaises(probe.ProbeError):
            probe.parse_crypto_bars(json.dumps([row]), START, END, closing)


if __name__ == '__main__':
    unittest.main()
