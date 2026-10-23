import argparse
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[1]


def checked(command, environment):
    result = subprocess.run(command, cwd=ROOT, env=environment, capture_output=True, timeout=600, check=False)
    if result.returncode != 0:
        raise RuntimeError('Clean deployment command failed; inspect the retained local project')
    return result.stdout.decode('utf-8').strip()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--port', type=int, default=18080)
    arguments = parser.parse_args()
    if not 1024 <= arguments.port <= 65535:
        raise ValueError('Invalid local port')
    project = 'investlens-acceptance-' + uuid4().hex[:12]
    directory = ROOT / 'backend/target' / project
    directory.mkdir(parents=True, exist_ok=False)
    environment = dict(os.environ)
    variables = set(re.findall(r'\$\{([A-Z][A-Z0-9_]*)', (ROOT / 'compose.yaml').read_text(encoding='utf-8')))
    for name in variables | {'COMPOSE_PROJECT_NAME', 'COMPOSE_FILE', 'COMPOSE_PROFILES', 'COMPOSE_ENV_FILES'}:
        environment.pop(name, None)
    settings = {'APP_PORT': str(arguments.port), 'MYSQL_DATABASE': 'investlens', 'MYSQL_USER': 'investlens',
                'MYSQL_PASSWORD': secrets.token_hex(32), 'MYSQL_ROOT_PASSWORD': secrets.token_hex(32),
                'REDIS_PASSWORD': secrets.token_hex(32), 'INVESTLENS_LLM_ENABLED': 'false',
                'LLM_BASE_URL': '', 'LLM_MODEL': '', 'LLM_API_KEY': '', 'DEEPSEEK_API_KEY': '',
                'INVESTLENS_MARKET_SINA_ENABLED': 'false', 'INVESTLENS_SINA_CACHE_SECONDS': '0'}
    env_file = directory / '.env'
    with env_file.open('x', encoding='utf-8') as handle:
        handle.write(''.join(name + '=' + value + '\n' for name, value in settings.items()))
    compose = ['docker', 'compose', '--env-file', str(env_file), '-f', 'compose.yaml', '--project-name', project]
    existing = checked(['docker', 'volume', 'ls', '--filter', 'name=^' + project + '_mysql_data$', '--format', '{{.Name}}'], environment)
    if existing:
        raise RuntimeError('Expected a never-used named volume')
    print('Clean project: ' + project, flush=True)
    print('Fresh credentials remain in ignored backend/target; existing deployments are not changed', flush=True)
    try:
        checked(compose + ['config', '--quiet'], environment)
        checked(compose + ['up', '-d', '--no-build', '--pull', 'never', '--wait', '--wait-timeout', '240'], environment)
        migrations = checked(compose + ['exec', '-T', 'mysql', 'sh', '-ec',
                             'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u "$MYSQL_USER" -D "$MYSQL_DATABASE" -Nse "SELECT COUNT(*) FROM flyway_schema_history WHERE success=1"'], environment)
        if migrations != '16':
            raise RuntimeError('Fresh migration sequence incomplete')
        report = directory / 'report.json'
        checked([sys.executable, '-B', 'scripts/check-deployment.py', '--project', project, '--env-file', str(env_file),
                 '--base-url', 'http://127.0.0.1:' + str(arguments.port), '--label', 'G39', '--faults', '--restart',
                 '--output', str(report)], environment)
        receipt_path = directory / 'report-receipt.json'
        receipt = json.loads(receipt_path.read_text(encoding='utf-8'))
        receipt.update({'initialVolumeAbsent': True, 'freshMigrations': 16, 'physicalNewMachine': False,
                        'buildMode': 'previously built local images', 'volumeRetained': project + '_mysql_data'})
        receipt_path.write_text(json.dumps(receipt, ensure_ascii=False, indent=2), encoding='utf-8')
        print('PASS: empty-volume four-container startup, migrations, report, Redis outage and restart', flush=True)
        print('PASS: receipt retained in backend/target/' + project + '/report-receipt.json', flush=True)
        print('NOT TESTED: new physical machine, uncached source build, qualified market data or online models', flush=True)
    finally:
        checked(compose + ['down'], environment)
        print('Only the isolated test containers/network stopped; named volume and receipt retained', flush=True)


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, ValueError, OSError, subprocess.SubprocessError):
        raise SystemExit('FAIL: clean deployment acceptance failed; no raw output or credentials printed') from None
