import json
from datetime import date
import unittest
from unittest.mock import patch
import sina_adapter as adapter


class AdapterTests(unittest.TestCase):
    def test_fixed_symbols_and_bounded_dates(self):
        body = dict(schemaVersion='1.0.0', symbol='GLD', start='2026-06-24', end='2026-10-21')
        self.assertEqual(adapter.request_input(json.dumps(body))[0], 'GLD')
        for changes in ({'symbol': '../secret'}, {'url': 'https://example.invalid'}, {'end': '2026-10-22'}, {'start': '2025-12-31'}, {'end': '2026-06-23'}):
            with self.subTest(changes=changes), self.assertRaises(adapter.probe.ProbeError):
                adapter.request_input(json.dumps({**body, **changes}))

    def test_prices_are_decimal_strings_and_bad_numbers_fail(self):
        for value, expected in [(10.25, '10.25'), ('0.0000000001', '0.0000000001'), (0, '0'), ('10.00', '10'), (-1, '-1')]:
            self.assertEqual(adapter.price(value), expected)
        for value in (None, True, 'nan', 'Infinity', '1e19', '0.00000000001'):
            with self.subTest(value=value), self.assertRaises(adapter.probe.ProbeError):
                adapter.price(value)

    def test_no_history_and_transport_errors_are_not_success(self):
        for code in ('TIMEOUT', 'ACCESS_DENIED', 'RATE_LIMITED'):
            with patch.object(adapter.probe, 'transport', return_value=({'failureCode': code}, b'')):
                with self.assertRaises(adapter.probe.ProbeError) as error:
                    adapter.fetch_history('AAPL', date(2026, 6, 24), date(2026, 6, 25))
                self.assertEqual(error.exception.code, code)
        with patch.object(adapter.probe, 'transport', return_value=({'failureCode': None}, b'')):
            with self.assertRaises(adapter.probe.ProbeError) as error:
                adapter.fetch_history('AAPL', date(2026, 6, 24), date(2026, 6, 25))
            self.assertEqual(error.exception.code, 'NO_HISTORY')

    def test_fixed_url_and_quality_anomalies_are_preserved(self):
        row = dict(date='2026-06-24', open=10, high=11, low=0, close=10)
        with patch.object(adapter.probe, 'transport', return_value=({'failureCode': None}, b'data')) as transport, \
                patch.object(adapter.probe, 'decode_sina_rows', return_value=[row, row]):
            rows = adapter.fetch_history('GLD', date(2026, 6, 24), date(2026, 6, 25))
        transport.assert_called_once_with('https://finance.sina.com.cn/staticdata/us/GLD', timeout=8)
        self.assertEqual(len(rows), 2)
        self.assertEqual(rows[0]['low'], '0')


if __name__ == '__main__':
    unittest.main()
