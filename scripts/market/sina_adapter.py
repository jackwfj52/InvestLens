"""One bounded stdin/stdout call. Never log or persist upstream payloads."""
import argparse
from datetime import date, datetime, timezone
from decimal import Decimal, InvalidOperation
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'probes'))
import probe

SYMBOLS = frozenset(('AAPL', 'GLD', 'SLV'))


def request_input(raw):
    body = probe.strict_json(raw)
    if not isinstance(body, dict) or set(body) != {'schemaVersion', 'symbol', 'start', 'end'}:
        raise probe.ProbeError('INVALID_REQUEST')
    if body['schemaVersion'] != '1.0.0' or body['symbol'] not in SYMBOLS:
        raise probe.ProbeError('INVALID_REQUEST')
    try:
        start, end = date.fromisoformat(body['start']), date.fromisoformat(body['end'])
    except (ValueError, TypeError):
        raise probe.ProbeError('INVALID_REQUEST') from None
    if start.year != 2026 or end.year != 2026 or not 0 <= (end - start).days <= 119:
        raise probe.ProbeError('INVALID_REQUEST')
    return body['symbol'], start, end


def price(value):
    if isinstance(value, bool) or value is None:
        raise probe.ProbeError('INVALID_PRICE')
    try:
        number = Decimal(str(value))
        if not number.is_finite() or abs(number) >= Decimal('1e18'):
            raise probe.ProbeError('INVALID_PRICE')
        result = format(number, 'f')
        if '.' in result:
            result = result.rstrip('0').rstrip('.')
        if len(result.partition('.')[2]) > 10:
            raise probe.ProbeError('INVALID_PRICE')
        return '0' if number == 0 else result
    except (InvalidOperation, ValueError):
        raise probe.ProbeError('INVALID_PRICE') from None


def fetch_history(symbol, start, end, node='node'):
    summary, raw = probe.transport(f'https://finance.sina.com.cn/staticdata/us/{symbol}', timeout=8)
    if summary['failureCode']:
        raise probe.ProbeError(summary['failureCode'])
    if not raw:
        raise probe.ProbeError('NO_HISTORY')
    rows = probe.decode_sina_rows(raw, symbol, start, end, node)
    if not rows:
        raise probe.ProbeError('NO_HISTORY')
    if len(rows) > 500:
        raise probe.ProbeError('INVALID_RESPONSE')
    # Preserve duplicates, zero prices and OHLC order for Java's quality policy.
    return [{'sessionDate': row['date'], **{key: price(row[key]) for key in ('open', 'high', 'low', 'close')}} for row in rows]


def main():
    args = argparse.ArgumentParser()
    args.add_argument('--node', default='node')
    args.add_argument('--summary-only', action='store_true', help='No prices in diagnostic output')
    options = args.parse_args()
    result = {'schemaVersion': '1.0.0', 'status': 'ERROR', 'bars': [], 'failureCode': None,
              'retrievedAt': None}
    try:
        raw = sys.stdin.buffer.read(4097)
        if len(raw) > 4096:
            raise probe.ProbeError('INVALID_REQUEST')
        symbol, start, end = request_input(raw)
        result['bars'] = fetch_history(symbol, start, end, options.node)
        result['status'] = 'OK'
    except probe.ProbeError as error:
        result['failureCode'] = error.code
    except Exception:
        result['failureCode'] = 'INVALID_RESPONSE'
    result['retrievedAt'] = datetime.now(timezone.utc).isoformat(timespec='milliseconds').replace('+00:00', 'Z')
    if options.summary_only:
        rows = result.pop('bars')
        result.update(sampleCount=len(rows), firstSession=min((r['sessionDate'] for r in rows), default=None),
                      lastSession=max((r['sessionDate'] for r in rows), default=None),
                      mainlandAccessVerified=False, explicitProxyEnabled=False,
                      adjustmentVerified=False, storagePolicy='METADATA_ONLY')
    print(json.dumps(result, ensure_ascii=True, allow_nan=False, separators=(',', ':')))


if __name__ == '__main__':
    main()
