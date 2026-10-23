"""Small, single-attempt G02 probes. Never print keys, request URLs or raw bodies."""
import argparse
import ast
from datetime import date, datetime, time as day_time, timedelta, timezone
from decimal import Decimal, InvalidOperation
from email.utils import parsedate_to_datetime
import json
from hashlib import sha256
from importlib import metadata
import os
from pathlib import Path
import re
import socket
import subprocess
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlsplit
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

VERSION = '1.0.0'
MAX_BYTES = 2 * 1024 * 1024
UTC = timezone.utc


class ProbeError(Exception):
    def __init__(self, code):
        self.code = code


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def stamp():
    return datetime.now(UTC).isoformat(timespec='milliseconds').replace('+00:00', 'Z')


def strict_json(raw):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ProbeError('DUPLICATE_JSON_KEY')
            result[key] = value
        return result
    try:
        return json.loads(raw, object_pairs_hook=pairs,
                          parse_constant=lambda _: (_ for _ in ()).throw(ProbeError('INVALID_JSON')))
    except (ValueError, UnicodeError):
        raise ProbeError('INVALID_JSON') from None


def load_env(path):
    """Explicit dotenv subset, no shell expansion, no modification of existing env."""
    if path is None:
        return
    try:
        lines = Path(path).read_text(encoding='utf-8-sig').splitlines()
    except OSError:
        raise ProbeError('ENV_FILE_UNREADABLE') from None
    for line in lines:
        line = line.strip()
        if not line or line.startswith('#'):
            continue
        if '=' not in line:
            raise ProbeError('INVALID_ENV_FILE')
        key, value = (part.strip() for part in line.split('=', 1))
        if not re.fullmatch(r'[A-Z][A-Z0-9_]*', key):
            raise ProbeError('INVALID_ENV_FILE')
        if value[:1] in ('"', "'"):
            if len(value) < 2 or value[-1] != value[0]:
                raise ProbeError('INVALID_ENV_FILE')
            value = value[1:-1]
        os.environ.setdefault(key, value)


def safe_label(value, default=None):
    return value if isinstance(value, str) and re.fullmatch(r'[A-Za-z0-9_.:/ -]{1,160}', value) else default


def integer(value):
    try:
        if isinstance(value, bool) or not re.fullmatch(r'\d{1,12}', str(value)):
            return None
        return int(value)
    except (ValueError, TypeError):
        return None


def retry_after(value):
    if integer(value) is not None:
        return {'seconds': integer(value)}
    try:
        parsed = parsedate_to_datetime(value)
        if parsed.tzinfo is not None:
            return {'at': parsed.astimezone(UTC).isoformat()}
    except (ValueError, TypeError, OverflowError):
        pass
    return None


def transport(url, *, timeout, payload=None, key=None, opener=None, page_headers=None):
    parts = urlsplit(url)
    if parts.username or parts.password or parts.fragment:
        raise ProbeError('INVALID_ENDPOINT')
    if parts.scheme != 'https' and not (parts.scheme == 'http' and parts.hostname in ('127.0.0.1', '::1')):
        raise ProbeError('HTTPS_REQUIRED')
    headers = {'Accept': 'application/json', 'User-Agent': 'InvestLens-Capability-Probe/1.0'}
    if page_headers:
        if not set(page_headers) <= {'Accept', 'Origin', 'Referer'}:
            raise ProbeError('INVALID_PAGE_HEADERS')
        headers.update(page_headers)
    if key:
        headers['Authorization'] = 'Bearer ' + key
    raw_payload = None if payload is None else json.dumps(payload).encode('utf-8')
    if payload is not None:
        headers['Content-Type'] = 'application/json'
    request = Request(url, data=raw_payload, headers=headers)
    started = time.monotonic()
    summary = {'attempts': 1, 'socketTimeoutSeconds': timeout, 'httpStatus': None,
               'latencyMs': None, 'creditsUsed': None, 'creditsLeft': None,
               'retryAfter': None, 'failureCode': None, 'redirectHost': None}
    response = None
    try:
        try:
            # Disable environment AND platform proxy discovery for deployment probes.
            response = (opener or build_opener(ProxyHandler({}), NoRedirect())).open(request, timeout=timeout)
        except HTTPError as error:
            response = error
        with response:
            summary['httpStatus'] = response.code
            summary['creditsUsed'] = integer(response.headers.get('api-credits-used'))
            summary['creditsLeft'] = integer(response.headers.get('api-credits-left'))
            summary['retryAfter'] = retry_after(response.headers.get('Retry-After'))
            if 300 <= response.code < 400:
                summary['redirectHost'] = safe_label(urlsplit(response.headers.get('Location', '')).hostname)
            raw = response.read(MAX_BYTES + 1)
        if len(raw) > MAX_BYTES:
            raise ProbeError('RESPONSE_TOO_LARGE')
        if not 200 <= summary['httpStatus'] < 300:
            summary['failureCode'] = 'REDIRECT_NOT_FOLLOWED' if 300 <= summary['httpStatus'] < 400 else {401: 'AUTHENTICATION_FAILED', 402: 'PAYMENT_REQUIRED',
                                      403: 'ACCESS_DENIED', 429: 'RATE_LIMITED'}.get(
                                          summary['httpStatus'], 'UPSTREAM_HTTP_ERROR')
        return summary, raw
    except (TimeoutError, socket.timeout):
        summary['failureCode'] = 'TIMEOUT'
    except URLError as error:
        summary['failureCode'] = 'TIMEOUT' if isinstance(error.reason, (TimeoutError, socket.timeout)) else 'NETWORK_ERROR'
    except OSError:
        summary['failureCode'] = 'NETWORK_ERROR'
    except ProbeError as error:
        summary['failureCode'] = error.code
    finally:
        summary['latencyMs'] = round((time.monotonic() - started) * 1000)
    return summary, b''


def validate_bars(rows, start, end):
    if not isinstance(rows, list) or not rows:
        raise ProbeError('NO_HISTORY')
    dates = []
    for row in rows:
        if not isinstance(row, dict):
            raise ProbeError('INVALID_BAR')
        try:
            session = date.fromisoformat(row['date'])
            prices = [Decimal(str(row[k])) for k in ('open', 'high', 'low', 'close')]
        except (KeyError, ValueError, TypeError, InvalidOperation):
            raise ProbeError('INVALID_BAR') from None
        if not all(p.is_finite() and p > 0 for p in prices):
            raise ProbeError('INVALID_PRICE')
        opening, high, low, closing = prices
        if not low <= min(opening, closing) <= max(opening, closing) <= high:
            raise ProbeError('INCONSISTENT_OHLC')
        if not start <= session <= end:
            raise ProbeError('BAR_OUTSIDE_CLOSED_WINDOW')
        dates.append(session)
    if len(set(dates)) != len(dates):
        raise ProbeError('DUPLICATE_SESSION')
    return {'sampleCount': len(dates), 'firstSession': min(dates).isoformat(),
            'lastSession': max(dates).isoformat(), 'minimumVolatilitySamplesAvailable': len(dates) >= 31,
            'calendarContinuityVerified': False, 'corporateActionsVerified': False}


def parse_twelve(raw, symbol, start, end):
    body = strict_json(raw)
    if not isinstance(body, dict):
        raise ProbeError('INVALID_RESPONSE')
    if body.get('status') == 'error':
        code = integer(body.get('code'))
        raise ProbeError({401: 'AUTHENTICATION_FAILED', 403: 'ACCESS_DENIED', 429: 'RATE_LIMITED'}.get(code, 'PROVIDER_REJECTED'))
    meta, values = body.get('meta'), body.get('values')
    if not isinstance(meta, dict) or not isinstance(values, list):
        raise ProbeError('INVALID_RESPONSE')
    if meta.get('symbol') != symbol or meta.get('interval') != '1day' or meta.get('currency') != 'USD':
        raise ProbeError('INSTRUMENT_METADATA_MISMATCH')
    timezone_name = safe_label(meta.get('exchange_timezone'))
    exchange = safe_label(meta.get('exchange'))
    if not timezone_name or not exchange:
        raise ProbeError('INCOMPLETE_MARKET_METADATA')
    bars = [{'date': row.get('datetime'), **{k: row.get(k) for k in ('open', 'high', 'low', 'close')}}
            for row in values if isinstance(row, dict)]
    if len(bars) != len(values):
        raise ProbeError('INVALID_BAR')
    return {**validate_bars(bars, start, end), 'observedMetadata': {
        'symbol': symbol, 'currency': 'USD', 'exchange': exchange,
        'mic': safe_label(meta.get('mic_code')), 'exchangeTimezone': timezone_name,
        'instrumentType': safe_label(meta.get('type'))},
        'adjustmentRequested': 'splits', 'adjustmentVerified': False}


def parse_yahoo(raw, symbol, start, end):
    body = strict_json(raw)
    if not isinstance(body, dict) or not isinstance(body.get('chart'), dict):
        raise ProbeError('INVALID_RESPONSE')
    chart = body['chart']
    if chart.get('error') or not isinstance(chart.get('result'), list) or len(chart['result']) != 1:
        raise ProbeError('PROVIDER_REJECTED')
    data = chart['result'][0]
    try:
        meta = data['meta']
        if meta['symbol'] != symbol or meta['currency'] != 'USD' or meta['dataGranularity'] != '1d':
            raise ProbeError('INSTRUMENT_METADATA_MISMATCH')
        zone = ZoneInfo(meta['exchangeTimezoneName'])
        quote = data['indicators']['quote'][0]
        timestamps = data['timestamp']
        if any(len(quote[k]) != len(timestamps) for k in ('open', 'high', 'low', 'close')):
            raise ProbeError('INVALID_BAR')
        bars = [{'date': datetime.fromtimestamp(ts, zone).date().isoformat(),
                 **{k: quote[k][i] for k in ('open', 'high', 'low', 'close')}}
                for i, ts in enumerate(timestamps)]
        result = validate_bars(bars, start, end)
        return {**result, 'observedMetadata': {
            'symbol': symbol, 'currency': 'USD', 'exchange': safe_label(meta.get('exchangeName')),
            'instrumentType': safe_label(meta.get('instrumentType')),
            'exchangeTimezone': safe_label(meta.get('exchangeTimezoneName'))},
            'adjustmentRequested': None, 'adjustmentVerified': False,
            'adjustedClosePresent': bool(data['indicators'].get('adjclose'))}
    except (KeyError, TypeError, IndexError, ValueError, OverflowError, OSError):
        raise ProbeError('INVALID_RESPONSE') from None


def parse_sina(raw, symbol, start, end, node='node'):
    """Decode DATA with a pinned local SDK algorithm; never execute remote JS."""
    try:
        text = raw.decode('ascii')
    except UnicodeError:
        raise ProbeError('UNRECOGNIZED_HISTORY_FORMAT') from None
    match = re.fullmatch(r'\s*(?:var\s+)?([A-Za-z_$][A-Za-z0-9_$]*)\s*=\s*"([A-Za-z0-9+/=]+)";?\s*', text)
    if not match or not match.group(1).upper().endswith(symbol.upper()):
        raise ProbeError('UNRECOGNIZED_HISTORY_FORMAT')
    try:
        package = metadata.distribution('akshare')
        if package.version != '1.19.1':
            raise ProbeError('DECODER_VERSION_MISMATCH')
        source_path = package.locate_file('akshare/stock/cons.py')
        tree = ast.parse(source_path.read_text(encoding='utf-8'))
        source = next(ast.literal_eval(n.value) for n in tree.body if isinstance(n, ast.Assign)
                      and any(isinstance(t, ast.Name) and t.id == 'zh_js_decode' for t in n.targets))
    except (metadata.PackageNotFoundError, OSError, StopIteration, ValueError, SyntaxError):
        raise ProbeError('SINA_DECODER_NOT_INSTALLED') from None
    if sha256(source.encode('utf-8')).hexdigest() != 'a42a1b100f70c858666ba74d9855a34e5b2a584c45e987e08eeaedf8d70b7167':
        raise ProbeError('DECODER_INTEGRITY_MISMATCH')
    wrapper = r'''
const fs = require('node:fs');
const vm = require('node:vm');
try {
  const input = JSON.parse(fs.readFileSync(0, 'utf8'));
  const decoded = vm.runInNewContext(input.source + ';d(payload)',
    {payload: input.payload}, {timeout: 1500, contextCodeGeneration: {strings: false, wasm: false}});
  process.stdout.write(JSON.stringify(decoded));
} catch (_) { process.exitCode = 2; }
'''
    try:
        output = subprocess.run([node, '-e', wrapper],
                                input=json.dumps({'source': source, 'payload': match.group(2)}),
                                text=True, encoding='utf-8', capture_output=True, timeout=5, check=False)
    except (OSError, subprocess.TimeoutExpired):
        raise ProbeError('SINA_DECODER_UNAVAILABLE') from None
    if output.returncode != 0 or len(output.stdout) > MAX_BYTES * 8:
        raise ProbeError('SINA_DECODE_FAILED')
    decoded = strict_json(output.stdout)
    if not isinstance(decoded, list):
        raise ProbeError('SINA_DECODE_FAILED')
    bars = []
    for row in decoded:
        try:
            session = date.fromisoformat(row['date'][:10])
        except (KeyError, TypeError, ValueError):
            raise ProbeError('SINA_DECODE_FAILED') from None
        if start <= session <= end:
            bars.append({'date': session.isoformat(), **{k: row.get(k) for k in ('open', 'high', 'low', 'close')}})
    return {**validate_bars(bars, start, end), 'decodedBarsVerified': True,
            'decoderVersion': 'akshare-1.19.1/zh_js_decode',
            'observedMetadata': {'symbol': symbol, 'currency': None, 'exchange': None,
                                 'instrumentType': None, 'exchangeTimezone': None},
            'metadataSource': 'NOT_INCLUDED_IN_HISTORY_PAYLOAD',
            'adjustmentRequested': 'unadjusted', 'adjustmentVerified': False}


def parse_baidu(raw, symbol, start, end):
    body = strict_json(raw)
    if not isinstance(body, dict) or str(body.get('ResultCode')) != '0':
        raise ProbeError('PROVIDER_REJECTED')
    try:
        result = body['Result']
        data = result['newMarketData']
        keys, values = data['keys'], data['marketData']
        if not isinstance(keys, list) or len(set(keys)) != len(keys) or not isinstance(values, str):
            raise ProbeError('INVALID_RESPONSE')
        if not {'time', 'open', 'high', 'low', 'close'} <= set(keys):
            raise ProbeError('INVALID_RESPONSE')
        bars = []
        excluded = 0
        for record in values.split(';'):
            if not record.strip():
                continue
            cells = record.split(',')
            if len(cells) != len(keys):
                raise ProbeError('INVALID_BAR')
            row = dict(zip(keys, cells))
            session = date.fromisoformat(row['time'])
            if not start <= session <= end:
                excluded += 1
                continue
            bars.append({'date': session.isoformat(), **{k: row[k] for k in ('open', 'high', 'low', 'close')}})
        basic = result.get('basicinfos', {})
        code = basic.get('code') or basic.get('stockCode')
        if code is not None and code.upper() != symbol:
            raise ProbeError('INSTRUMENT_METADATA_MISMATCH')
        return {**validate_bars(bars, start, end), 'excludedOutsideWindow': excluded,
                'observedMetadata': {'symbol': symbol, 'currency': safe_label(basic.get('currency')),
                                     'exchange': safe_label(basic.get('exchange')),
                                     'instrumentType': None, 'exchangeTimezone': None},
                'metadataSource': 'PARTIAL_OR_NOT_INCLUDED_IN_HISTORY_PAYLOAD',
                'adjustmentRequested': None, 'adjustmentVerified': False}
    except (KeyError, TypeError, ValueError, AttributeError):
        raise ProbeError('INVALID_RESPONSE') from None


def market_probe(args):
    zone = ZoneInfo('America/New_York')
    end = date.fromisoformat(args.end_date) if args.end_date else datetime.now(zone).date() - timedelta(days=1)
    if end >= datetime.now(zone).date():
        raise ProbeError('END_DATE_MUST_PRECEDE_CURRENT_US_SESSION')
    start = end - timedelta(days=120)
    candidates = [('STOCK', args.stock), ('GOLD_ETF', args.gold), ('SILVER_ETF', args.silver)]
    if len({symbol for _, symbol in candidates}) != 3 or any(not re.fullmatch(r'[A-Z][A-Z0-9.-]{0,15}', symbol) for _, symbol in candidates):
        raise ProbeError('INVALID_CANDIDATE_SYMBOLS')
    key = os.environ.get('TWELVE_DATA_API_KEY', '').strip()
    results = []
    for role, symbol in candidates:
        result = {'role': role, 'symbol': symbol, 'status': 'BLOCKED', 'failureCode': None}
        if args.provider == 'twelvedata' and not key:
            result['failureCode'] = 'PROVIDER_NOT_CONFIGURED'
            result['attempts'] = 0
            results.append(result)
            continue
        if args.provider == 'twelvedata':
            url = 'https://api.twelvedata.com/time_series?' + urlencode({
                'symbol': symbol, 'interval': '1day', 'start_date': start.isoformat(),
                'end_date': end.isoformat(), 'outputsize': 64, 'order': 'asc',
                'adjust': 'splits', 'timezone': 'Exchange', 'apikey': key})
            parser = parse_twelve
        elif args.provider == 'yahoo':
            url = f'https://query1.finance.yahoo.com/v8/finance/chart/{symbol}?' + urlencode({
                'period1': int(datetime.combine(start, day_time(), zone).timestamp()),
                'period2': int(datetime.combine(end + timedelta(days=1), day_time(), zone).timestamp()),
                'interval': '1d', 'events': 'div,splits'})
            parser = parse_yahoo
        elif args.provider == 'baidu':
            url = 'https://finance.pae.baidu.com/selfselect/getstockquotation?' + urlencode({
                'all': '1', 'isIndex': 'false', 'isBk': 'false', 'isBlock': 'false',
                'isFutures': 'false', 'isStock': 'true', 'newFormat': '1',
                'group': 'quotation_kline_us', 'finClientType': 'pc', 'code': symbol,
                'start_time': start.isoformat(), 'ktype': '1'})
            parser = parse_baidu
        else:
            url = f'https://finance.sina.com.cn/staticdata/us/{symbol}'
            parser = lambda raw, symbol, start, end: parse_sina(raw, symbol, start, end, args.node)
        page_headers = {'Accept': 'application/vnd.finance-web.v1+json',
                        'Origin': 'https://finance.baidu.com',
                        'Referer': f'https://finance.baidu.com/stock/us-{symbol}'} if args.provider == 'baidu' else None
        summary, raw = transport(url, timeout=args.timeout, page_headers=page_headers)
        result.update(summary)
        if summary['failureCode'] is None:
            try:
                result.update(parser(raw, symbol, start, end))
                if args.provider in ('twelvedata', 'yahoo'):
                    observed = result['observedMetadata']
                    accepted_types = ('EQUITY', 'Common Stock', 'Stock') if role == 'STOCK' else ('ETF',)
                    if observed['instrumentType'] not in accepted_types or observed['exchangeTimezone'] != 'America/New_York':
                        raise ProbeError('INSTRUMENT_METADATA_MISMATCH')
                if not result['minimumVolatilitySamplesAvailable']:
                    raise ProbeError('INSUFFICIENT_HISTORY')
                result['status'] = 'PASSED'
            except ProbeError as error:
                result['failureCode'] = error.code
        results.append(result)
    passed = all(item['status'] == 'PASSED' for item in results)
    return {'provider': args.provider, 'technicalStatus': 'PASSED' if passed else 'BLOCKED',
            'qualificationStatus': 'PENDING_USAGE_AND_DATA_REVIEW', 'productionAllowlist': [],
            'networkRequirement': 'CN_MAINLAND_DIRECT', 'diagnosticOnly': args.provider == 'yahoo',
            'requestedWindow': {'start': start.isoformat(), 'end': end.isoformat(), 'interval': '1d'},
            'rawPayloadStored': False, 'results': results}


def llm_probe(args):
    key = os.environ.get(args.key_env, '').strip()
    if not key:
        return {'technicalStatus': 'BLOCKED', 'failureCode': 'PROVIDER_NOT_CONFIGURED', 'attempts': 0}
    base = args.base_url.rstrip('/')
    parts = urlsplit(base)
    if parts.query or parts.fragment or parts.username or parts.password:
        raise ProbeError('INVALID_ENDPOINT')
    if not re.fullmatch(r'[A-Za-z0-9_./:-]{1,160}', args.model):
        raise ProbeError('INVALID_MODEL_NAME')
    expected = {'probe': 'investlens', 'ok': True}
    payload = {'model': args.model, 'messages': [
        {'role': 'system', 'content': 'Return only a JSON object. Do not call tools.'},
        {'role': 'user', 'content': 'Return exactly this JSON: {"probe":"investlens","ok":true}'}],
        'max_tokens': 128, 'stream': False,
        'response_format': {'type': 'json_object'}}
    if args.disable_thinking:
        payload['thinking'] = {'type': 'disabled'}
    summary, raw = transport(base + '/chat/completions', timeout=args.timeout, payload=payload, key=key)
    result = {'technicalStatus': 'BLOCKED', 'requestedModel': args.model,
              'requestedFormat': 'json_object', 'serverStrictSchemaGuaranteed': False,
              'rawPayloadStored': False, **summary}
    if summary['failureCode']:
        return result
    try:
        body = strict_json(raw)
        choice = body['choices'][0]
        if not isinstance(choice, dict) or not isinstance(body.get('usage', {}), dict):
            raise ProbeError('MODEL_RESPONSE_INVALID')
        result['finishReason'] = safe_label(choice.get('finish_reason'))
        result['returnedModel'] = safe_label(body.get('model'))
        usage = body.get('usage', {})
        result['usage'] = {key: integer(usage.get(key)) for key in ('prompt_tokens', 'completion_tokens', 'total_tokens')}
        if choice.get('finish_reason') != 'stop':
            raise ProbeError('MODEL_TRUNCATED_OR_INCOMPLETE')
        content = choice['message']['content']
        if not isinstance(content, str) or not content.strip():
            raise ProbeError('MODEL_EMPTY_OUTPUT')
        parsed = strict_json(content)
        if parsed != expected or not isinstance(parsed, dict) or type(parsed.get('ok')) is not bool:
            raise ProbeError('MODEL_OUTPUT_SHAPE_INVALID')
        result.update(technicalStatus='PASSED', jsonParsePassed=True, localShapeValidationPassed=True)
    except (KeyError, IndexError, TypeError, AttributeError):
        result['failureCode'] = 'MODEL_RESPONSE_INVALID'
    except ProbeError as error:
        result['failureCode'] = error.code
    return result


def site_probe(args):
    """Inspect documented domestic pages/data formats; never execute page JS."""
    results = []
    for symbol in ('AAPL', 'GLD', 'SLV'):
        if args.provider == 'sina':
            endpoints = [('QUOTE_PAGE', f'https://stock.finance.sina.com.cn/usstock/quotes/{symbol}.html'),
                         ('DAILY_HISTORY', f'https://finance.sina.com.cn/staticdata/us/{symbol}')]
        elif args.provider == 'baidu':
            endpoints = [('QUOTE_PAGE', f'https://finance.baidu.com/stock/us-{symbol}')]
        else:
            endpoints = [('QUOTE_PAGE', f'https://stockpage.10jqka.com.cn/{symbol}/')]
        for kind, url in endpoints:
            summary, raw = transport(url, timeout=args.timeout)
            result = {'symbol': symbol, 'kind': kind, 'status': 'BLOCKED', **summary}
            if summary['failureCode'] is None:
                text = raw.decode('utf-8', errors='replace')
                if any(mark in text.lower() for mark in ('verify your browser', 'captcha', '访问过于频繁', '请输入验证码')):
                    result['failureCode'] = 'HUMAN_VERIFICATION_REQUIRED'
                elif kind == 'DAILY_HISTORY':
                    match = re.fullmatch(r'\s*(?:var\s+)?([A-Za-z_$][A-Za-z0-9_$]*)\s*=\s*"([A-Za-z0-9+/=]+)";?\s*', text)
                    if match:
                        result.update(status='ENCODED_HISTORY_AVAILABLE', encodedCharacters=len(match.group(2)),
                                      decodedBarsVerified=False)
                    else:
                        result['failureCode'] = 'UNRECOGNIZED_HISTORY_FORMAT'
                elif '<html' in text.lower() and symbol.lower() in text.lower():
                    result.update(status='PAGE_AVAILABLE', dailyBarsVerified=False)
                else:
                    result['failureCode'] = 'UNRECOGNIZED_PAGE'
            results.append(result)
    return {'provider': args.provider, 'technicalStatus': 'INCOMPLETE',
            'qualificationStatus': 'PENDING_HISTORY_AND_USAGE_REVIEW',
            'productionAllowlist': [], 'rawPayloadStored': False, 'results': results}


def parse_crypto_bars(raw, start, end, end_ms):
    data = strict_json(raw)
    if not isinstance(data, list):
        raise ProbeError('PROVIDER_REJECTED')
    bars = []
    for row in data:
        if not isinstance(row, list) or len(row) != 12 or type(row[0]) is not int or type(row[6]) is not int:
            raise ProbeError('INVALID_BAR')
        if row[6] > end_ms or row[6] - row[0] != 86_400_000 - 1:
            raise ProbeError('UNCLOSED_OR_WRONG_INTERVAL_BAR')
        session = datetime.fromtimestamp(row[0] / 1000, UTC)
        if session.time() != day_time():
            raise ProbeError('NON_UTC_DAILY_BOUNDARY')
        bars.append({'date': session.date().isoformat(), 'open': row[1], 'high': row[2], 'low': row[3], 'close': row[4]})
    return validate_bars(bars, start, end)


def crypto_probe(args):
    """Conditional public-data fallback test, not a P0 asset-scope switch."""
    end = datetime.now(UTC).date() - timedelta(days=1)
    start = end - timedelta(days=63)
    end_ms = int(datetime.combine(end + timedelta(days=1), day_time(), UTC).timestamp() * 1000) - 1
    results = []
    for symbol in ('BTCUSDT', 'ETHUSDT'):
        url = 'https://data-api.binance.vision/api/v3/klines?' + urlencode({
            'symbol': symbol, 'interval': '1d', 'limit': 64, 'endTime': end_ms})
        summary, raw = transport(url, timeout=args.timeout)
        result = {'symbol': symbol, 'status': 'BLOCKED', **summary}
        if summary['failureCode'] is None:
            try:
                result.update(parse_crypto_bars(raw, start, end, end_ms))
                if not result['minimumVolatilitySamplesAvailable']:
                    raise ProbeError('INSUFFICIENT_HISTORY')
                result['status'] = 'PASSED'
            except (ProbeError, ValueError, OverflowError, OSError) as error:
                result['failureCode'] = error.code if isinstance(error, ProbeError) else 'INVALID_RESPONSE'
        results.append(result)
    return {'provider': 'binance-public-market-data', 'technicalStatus': 'PASSED' if all(r['status'] == 'PASSED' for r in results) else 'BLOCKED',
            'scope': 'CONDITIONAL_CRYPTO_FALLBACK', 'p0ScopeChanged': False,
            'networkRequirement': 'CN_MAINLAND_DIRECT', 'qualificationStatus': 'PENDING_NETWORK_USAGE_AND_SCOPE_REVIEW',
            'quoteAsset': 'USDT', 'quoteAssetIsUSD': False, 'calendar': 'UTC_24X7', 'annualizationFactor': 365,
            'productionAllowlist': [], 'rawPayloadStored': False, 'results': results}


def serialize_report(report):
    secrets = {value for name, value in os.environ.items()
               if re.search(r'(?:KEY|TOKEN|SECRET)$', name) and value}

    def redact(value):
        if isinstance(value, str):
            for secret in sorted(secrets, key=len, reverse=True):
                value = value.replace(secret, '[REDACTED]')
            return value
        if isinstance(value, dict):
            return {key: redact(item) for key, item in value.items()}
        if isinstance(value, list):
            return [redact(item) for item in value]
        return value
    return json.dumps(redact(report), ensure_ascii=False, indent=2, allow_nan=False) + '\n'


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--env-file', help='explicit ignored .env file; existing environment wins')
    parser.add_argument('--output', help='write sanitized summary JSON, never raw responses')
    commands = parser.add_subparsers(dest='command', required=True)
    market = commands.add_parser('market')
    market.add_argument('--provider', choices=('sina', 'baidu', 'twelvedata', 'yahoo'), default='sina')
    market.add_argument('--node', default='node', help='local Node.js executable for pinned Sina decoder')
    market.add_argument('--stock')
    market.add_argument('--gold')
    market.add_argument('--silver')
    market.add_argument('--end-date', help='last session date, before current US calendar date')
    market.add_argument('--timeout', type=int, default=8, choices=range(1, 31))
    llm = commands.add_parser('llm')
    llm.add_argument('--base-url')
    llm.add_argument('--model')
    llm.add_argument('--key-env', default='DEEPSEEK_API_KEY')
    llm.add_argument('--disable-thinking', action='store_true')
    llm.add_argument('--timeout', type=int, default=30, choices=range(1, 31))
    sites = commands.add_parser('site')
    sites.add_argument('--provider', choices=('sina', 'baidu', 'ths'), required=True)
    sites.add_argument('--timeout', type=int, default=8, choices=range(1, 31))
    crypto = commands.add_parser('crypto')
    crypto.add_argument('--timeout', type=int, default=8, choices=range(1, 31))
    args = parser.parse_args(argv)
    report = {'schemaVersion': VERSION, 'probeType': args.command, 'observedAt': stamp(),
              'networkContext': {'proxyPolicy': 'DISABLED', 'mainlandAccessVerified': False,
                                 'executionLocation': 'UNVERIFIED',
                                 'transparentTunnelExcluded': False}}
    try:
        load_env(args.env_file)
        if args.command == 'market':
            args.stock = args.stock or os.environ.get('PROBE_STOCK_SYMBOL', 'AAPL')
            args.gold = args.gold or os.environ.get('PROBE_GOLD_ETF_SYMBOL', 'GLD')
            args.silver = args.silver or os.environ.get('PROBE_SILVER_ETF_SYMBOL', 'SLV')
        elif args.command == 'llm':
            args.base_url = args.base_url or os.environ.get('LLM_BASE_URL', 'https://api.deepseek.com')
            args.model = args.model or os.environ.get('LLM_MODEL', 'deepseek-flash')
            if not re.fullmatch(r'[A-Z][A-Z0-9_]*', args.key_env):
                raise ProbeError('INVALID_KEY_ENV_NAME')
        report.update({'market': market_probe, 'llm': llm_probe, 'site': site_probe, 'crypto': crypto_probe}[args.command](args))
    except ZoneInfoNotFoundError:
        report.update(technicalStatus='BLOCKED', failureCode='TIMEZONE_DATABASE_MISSING')
    except (ValueError, ProbeError) as error:
        report.update(technicalStatus='BLOCKED', failureCode=error.code if isinstance(error, ProbeError) else 'INVALID_CONFIGURATION')
    rendered = serialize_report(report)
    if args.output:
        try:
            target = Path(args.output)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(rendered, encoding='utf-8')
        except OSError:
            print('{"technicalStatus":"BLOCKED","failureCode":"OUTPUT_UNWRITABLE"}')
            return 2
    print(rendered, end='')
    return 0 if report.get('technicalStatus') == 'PASSED' else 2


if __name__ == '__main__':
    sys.exit(main())
