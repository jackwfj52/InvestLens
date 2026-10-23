import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
from urllib.parse import urlsplit
from urllib.request import ProxyHandler, build_opener

ROOT = Path(__file__).resolve().parents[1]
HTTP = build_opener(ProxyHandler({}))
SECRET_NAMES = {'MYSQL_PASSWORD', 'MYSQL_ROOT_PASSWORD', 'REDIS_PASSWORD', 'LLM_API_KEY', 'DEEPSEEK_API_KEY'}


def require(condition):
    if not condition:
        raise RuntimeError('Delivery check failed; inspect local artifacts without disclosing credentials')


def command(arguments):
    result = subprocess.run(arguments, cwd=ROOT, capture_output=True, timeout=60, check=False)
    require(result.returncode == 0)
    return result.stdout


def credentials():
    values = {os.environ.get(name, '') for name in SECRET_NAMES}
    path = ROOT / '.env'
    if path.is_file():
        for line in path.read_text(encoding='utf-8-sig').splitlines():
            name, separator, value = line.partition('=')
            if separator and name.strip() in SECRET_NAMES:
                values.add(value.strip().strip('"\''))
    return [value.encode('utf-8') for value in values if len(value) >= 12]


def no_secrets(body, values):
    require(not any(value in body for value in values))


def read(base, path, values):
    require(path.startswith('/') and not path.startswith('//'))
    with HTTP.open(base + path, timeout=12) as response:
        body = response.read(1048577)
    require(len(body) <= 1048576)
    no_secrets(body, values)
    return body


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--receipt', default='backend/target/g40-demo-report-receipt.json')
    arguments = parser.parse_args()
    receipt_path = (ROOT / arguments.receipt).resolve()
    require(receipt_path.is_relative_to((ROOT / 'backend/target').resolve()))
    receipt = json.loads(receipt_path.read_text(encoding='utf-8'))
    require(receipt_path.name.endswith('-receipt.json'))
    report_path = receipt_path.with_name(receipt_path.name.removesuffix('-receipt.json') + '.json')
    require(hashlib.sha256(report_path.read_bytes()).hexdigest() == receipt['reportSha256'])
    for name, digest in receipt['fixtureSha256'].items():
        require(name in ('portfolio-create.json', 'decision-ready.json'))
        require(hashlib.sha256((ROOT / 'backend/src/test/resources' / name).read_bytes()).hexdigest() == digest)
    require(receipt['dataMode'] == 'SYNTHETIC' and receipt['modelAttempts'] == receipt['dataAttempts'] == 0)
    base = receipt['baseUrl'];url = urlsplit(base)
    require(url.scheme == 'http' and url.hostname in ('127.0.0.1', 'localhost') and url.path == ''
            and not url.query and not url.fragment and url.username is None and url.password is None)
    require(re.fullmatch(r'[a-z0-9][a-z0-9_-]{0,62}', receipt['project']))
    values = credentials()
    require(values)
    files = command(['git', 'ls-files', '-z']).decode('utf-8').split('\0')
    for name in filter(None, files):
        path = Path(name)
        require(not (path.name.startswith('.env') and path.name != '.env.example'))
        require(path.suffix not in ('.pem', '.key', '.log') and 'node_modules' not in path.parts and 'target' not in path.parts)
        if (ROOT / path).is_file():
            no_secrets((ROOT / path).read_bytes(), values)
    for path in (ROOT / 'frontend/dist').rglob('*'):
        if path.is_file():
            no_secrets(path.read_bytes(), values)
    compose = ['docker', 'compose', '--env-file', '.env', '-f', 'compose.yaml', '--project-name', receipt['project']]
    require(command(compose + ['port', 'frontend', '8080']).decode().strip() == '127.0.0.1:' + str(url.port or 80))
    no_secrets(command(compose + ['logs', '--no-color', '--tail', '1000', 'backend', 'frontend']), values)
    html = read(base, '/workbench', values).decode('utf-8')
    assets = set(re.findall(r'/assets/[A-Za-z0-9_.-]+\.(?:js|css)', html))
    require(assets)
    for path in assets:
        read(base, path, values)
    report = json.loads(read(base, '/api/runs/' + receipt['runId'] + '/report', values))['data']
    require(report == json.loads(report_path.read_text(encoding='utf-8')))
    for path in ('/api/runs/' + receipt['runId'], '/api/runs/' + receipt['runId'] + '/evidence',
                 '/api/runs/' + receipt['runId'] + '/report/figures', '/api/runs/' + receipt['runId'] + '/events',
                 '/api/decisions/' + receipt['decisionId'] + '/runs', '/api/system/resilience'):
        read(base, path, values)
    print('PASS: fixture/report hashes and current published report match the receipt')
    print('PASS: no current configured credential values in tracked files, built/served assets, sampled API bodies or recent application logs')
    print('Scope: current local .env/environment values of at least twelve characters; not a historical secret scan or penetration test')


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, ValueError, KeyError, OSError, subprocess.SubprocessError):
        raise SystemExit('FAIL: delivery audit failed; no raw response, log output or credentials printed') from None
