import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request, ProxyHandler, build_opener
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[1]
HTTP = build_opener(ProxyHandler({}))
COMPOSE_OPTIONS = ['--env-file', '.env', '-f', 'compose.yaml']


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def base_url(value):
    parsed = urlsplit(value)
    require(parsed.scheme == 'http' and parsed.hostname in ('127.0.0.1', 'localhost')
            and parsed.username is None and parsed.password is None and not parsed.query
            and not parsed.fragment and parsed.path in ('', '/'), 'Only a local deployment URL is allowed')
    return value.rstrip('/')


def compose(*arguments):
    result = subprocess.run(['docker', 'compose', *COMPOSE_OPTIONS, *arguments],
                            cwd=ROOT, capture_output=True, timeout=240, check=False)
    require(result.returncode == 0, 'Deployment command failed; inspect local service status')
    return result.stdout.decode('utf-8').strip()


def request(base, path, payload=None, headers=None):
    body = None if payload is None else json.dumps(payload, ensure_ascii=False).encode('utf-8')
    outgoing = {'Accept': 'application/json', **(headers or {})}
    if body is not None:
        outgoing['Content-Type'] = 'application/json'
    with HTTP.open(Request(base + path, data=body, headers=outgoing), timeout=12) as response:
        data = json.load(response)
        require(data.get('schemaVersion') == '1.0.0' and data.get('code') == 'OK', 'Unexpected API response')
        return data['data']


def ready(base):
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        try:
            with HTTP.open(base + '/actuator/health/readiness', timeout=6) as response:
                if json.load(response).get('status') == 'UP':
                    return
        except (HTTPError, URLError, TimeoutError, OSError, ValueError):
            pass
        time.sleep(1)
    raise RuntimeError('Deployment readiness timed out')


def run(base, decision, portfolio, scenario):
    payload = {'schemaVersion': '1.0.0', 'decisionVersion': decision['version'],
               'portfolioSnapshotId': portfolio['snapshotId'], 'portfolioVersion': portfolio['version'],
               'dataMode': 'SYNTHETIC', 'scenarioId': scenario['scenarioId']}
    key = 'acceptance_' + str(uuid4())
    created = request(base, '/api/decisions/' + decision['decisionId'] + '/runs', payload, {'Idempotency-Key': key})
    duplicate = request(base, '/api/decisions/' + decision['decisionId'] + '/runs', payload, {'Idempotency-Key': key})
    require(created['runId'] == duplicate['runId'], 'Idempotent retry created a different run')
    deadline = time.monotonic() + 125
    while time.monotonic() < deadline:
        current = request(base, '/api/runs/' + created['runId'])
        if current['status'] in ('COMPLETED', 'PARTIAL', 'FAILED', 'CANCELLED'):
            require(current['status'] == 'PARTIAL' and current['reportId'], 'Synthetic run did not publish a partial report')
            require(current['budget']['modelAttempts'] == 0 and current['budget']['dataAttempts'] == 0, 'Smoke test made an external call')
            return current
        time.sleep(0.2)
    raise RuntimeError('Synthetic workflow timed out')


def events(base, run_id):
    page = request(base, '/api/runs/' + run_id + '/events?afterSequence=0')
    require([item['sequence'] for item in page['items']] == list(range(1, page['nextSequence'] + 1)), 'Missing persisted event sequence')
    require(page['items'][-1]['type'] == 'RUN_TERMINATED', 'Missing terminal event')
    outgoing = Request(base + '/api/runs/' + run_id + '/events?afterSequence=0', headers={
        'Accept': 'text/event-stream', 'Last-Event-ID': run_id + ':1'})
    with HTTP.open(outgoing, timeout=12) as response:
        require(response.headers.get_content_type() == 'text/event-stream', 'Proxy did not preserve SSE content type')
        stream = response.read(262145).decode('utf-8')
    identifiers = [line[3:].strip() for line in stream.splitlines() if line.startswith('id:')]
    expected = [run_id + ':' + str(index) for index in range(2, page['nextSequence'] + 1)]
    require(identifiers == expected, 'SSE proxy cursor replay is incomplete or duplicated')
    print('PASS: API/SSE proxy, event order, Last-Event-ID and terminal close')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--base-url', default='http://127.0.0.1:8080')
    parser.add_argument('--faults', action='store_true')
    parser.add_argument('--restart', action='store_true')
    parser.add_argument('--project')
    parser.add_argument('--env-file', default='.env')
    parser.add_argument('--label', default='G37')
    parser.add_argument('--output', default='backend/target/g37-deployment-report.json')
    arguments = parser.parse_args()
    base = base_url(arguments.base_url)
    require(re.fullmatch(r'[A-Za-z0-9_-]{1,40}', arguments.label), 'Invalid acceptance label')
    if arguments.project:
        require(re.fullmatch(r'[a-z0-9][a-z0-9_-]{0,62}', arguments.project), 'Invalid Compose project')
    target = (ROOT / arguments.output).resolve()
    require(target.is_relative_to((ROOT / 'backend/target').resolve()) and target.suffix == '.json',
            'Output must be an ignored backend/target JSON artifact')
    COMPOSE_OPTIONS[:] = ['--env-file', arguments.env_file, '-f', 'compose.yaml']
    if arguments.project:
        COMPOSE_OPTIONS.extend(['--project-name', arguments.project])
    binding = compose('port', 'frontend', '8080')
    require(binding == '127.0.0.1:' + str(urlsplit(base).port or 80),
            'Local URL does not match the selected Compose deployment; no faults applied')
    compose('exec', '-T', 'backend', 'python', '-c',
            "import os; assert os.environ.get('INVESTLENS_LLM_ENABLED', 'false').lower() == 'false'")
    ready(base)
    with HTTP.open(base + '/workbench', timeout=10) as response:
        require(response.status == 200 and b'id="app"' in response.read(), 'SPA entry unavailable')
    portfolio_input = json.loads((ROOT / 'backend/src/test/resources/portfolio-create.json').read_text(encoding='utf-8'))
    portfolio_input['name'] = arguments.label + ' deployment synthetic smoke'
    portfolio = request(base, '/api/portfolios', portfolio_input)
    draft = json.loads((ROOT / 'backend/src/test/resources/decision-ready.json').read_text(encoding='utf-8'))
    draft['draftId'] = 'draft_acceptance_' + str(uuid4())
    draft['inputStatus'] = 'CONFIRMED'
    draft['inputText'] = arguments.label + ' deployment synthetic smoke; no real investment or external model call'
    draft['proposedDecision']['portfolioSnapshotId'] = portfolio['snapshotId']
    decision = request(base, '/api/decisions', draft)
    snapshot = request(base, '/api/calculation-snapshots', {
        'schemaVersion': '1.0.0', 'portfolioSnapshotId': portfolio['snapshotId'], 'marketInputs': []})
    scenario = request(base, '/api/scenarios', {'schemaVersion': '1.0.0', 'decisionId': decision['decisionId'],
        'decisionVersion': decision['version'], 'calculationSnapshotId': snapshot['snapshotId'],
        'portfolioSnapshotId': portfolio['snapshotId'], 'shocks': [
            {'instrumentId': 'ETF:SYNTHETIC:DEMO_GOLD:USD', 'shock': '-0.2'},
            {'instrumentId': 'ETF:SYNTHETIC:DEMO_SILVER:USD', 'shock': '-0.25'},
            {'instrumentId': 'CASH:USD', 'shock': '0'}]})
    completed = run(base, decision, portfolio, scenario)
    run_id = completed['runId']
    report = request(base, '/api/runs/' + run_id + '/report')
    require(report['dataMode'] == 'SYNTHETIC' and report['generationMode'] == 'DETERMINISTIC_FALLBACK', 'Incorrect report provenance')
    require(report['runId'] == run_id and report['snapshotHash'] == completed['snapshotHash'], 'Incorrect report binding')
    figures = request(base, '/api/runs/' + run_id + '/report/figures')
    require(any(item['name'] == 'SCENARIO_RETURN_AFTER' and item['displayValue'] == '-8.50%' for item in figures), 'Incorrect backend scenario value')
    events(base, run_id)
    print('PASS: SPA, fixed synthetic inputs, idempotent background run and published report')
    if arguments.faults:
        try:
            compose('stop', 'redis')
            require(request(base, '/api/system/resilience')['cache'] == 'UNAVAILABLE', 'Redis outage not exposed as degraded')
            ready(base)
            require(request(base, '/api/runs/' + run_id + '/report') == report, 'Report changed or disappeared during Redis outage')
            run(base, decision, portfolio, scenario)
            print('PASS: stopped Redis preserves readiness, report and background publication')
        finally:
            compose('start', 'redis')
    if arguments.restart:
        compose('restart', 'mysql', 'backend')
        ready(base)
        require(request(base, '/api/runs/' + run_id + '/report') == report, 'Report changed after container restart')
        events(base, run_id)
        print('PASS: MySQL/backend restart preserves original report and event history')
    history = request(base, '/api/decisions/' + decision['decisionId'] + '/runs')
    require(any(item['runId'] == run_id and item['reportId'] == report['reportId'] for item in history['items']), 'History cannot reopen original report')
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    version = subprocess.run(['git', 'rev-parse', 'HEAD'], cwd=ROOT, capture_output=True, timeout=10, check=True)
    changes = subprocess.run(['git', 'status', '--porcelain'], cwd=ROOT, capture_output=True, timeout=10, check=True)
    receipt = {'schemaVersion': '1.0.0', 'label': arguments.label,
               'executedAt': datetime.now(timezone.utc).isoformat(), 'sourceCommit': version.stdout.decode().strip(),
               'workingTreeDirty': bool(changes.stdout.strip()),
               'project': arguments.project or 'investlens', 'baseUrl': base,
               'portfolioSnapshotId': portfolio['snapshotId'], 'decisionId': decision['decisionId'],
               'calculationSnapshotId': snapshot['snapshotId'], 'scenarioId': scenario['scenarioId'],
               'runId': run_id, 'reportId': report['reportId'], 'snapshotHash': report['snapshotHash'],
               'dataMode': report['dataMode'], 'status': report['status'], 'generationMode': report['generationMode'],
               'modelAttempts': completed['budget']['modelAttempts'], 'dataAttempts': completed['budget']['dataAttempts'],
               'redisFaultPassed': arguments.faults, 'restartPassed': arguments.restart,
               'reportSha256': hashlib.sha256(target.read_bytes()).hexdigest(),
               'fixtureSha256': {name: hashlib.sha256((ROOT / 'backend/src/test/resources' / name).read_bytes()).hexdigest()
                                 for name in ('portfolio-create.json', 'decision-ready.json')},
               'images': json.loads(compose('images', '--format', 'json'))}
    target.with_name(target.stem + '-receipt.json').write_text(json.dumps(receipt, ensure_ascii=False, indent=2), encoding='utf-8')
    print('PASS: deployment smoke complete; only synthetic local records retained')


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, HTTPError, URLError, TimeoutError, OSError, ValueError, subprocess.TimeoutExpired):
        raise SystemExit('FAIL: deployment smoke failed; inspect local readiness/status; no raw response or credentials printed') from None
