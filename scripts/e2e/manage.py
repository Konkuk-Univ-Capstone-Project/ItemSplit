#!/usr/bin/env python3
"""Reuse the existing local OCR E2E case. Never seed, reset, or invoke OCR."""
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import signal
import socket
import subprocess
import sys
import tarfile
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
STATE = ROOT / '.local/e2e'
CONTAINER = 'itemsplit-e2e-postgres'
VOLUME = 'itemsplit-e2e_itemsplit-postgres-data'
API = 'http://127.0.0.1:18080'
WEB = 'http://127.0.0.1:5173'
COMPOSE = ['docker', 'compose', '-p', 'itemsplit-e2e', '-f', str(ROOT / 'scripts/e2e/compose.yml')]
rate_remaining = None

def run(command, **kwargs):
    return subprocess.run(command, check=True, cwd=ROOT, **kwargs)

def fixture():
    path = STATE / 'fixture.json'
    if not path.exists():
        raise RuntimeError('보존된 .local/e2e/fixture.json이 없습니다. 기존 백업을 복구하세요. 테스트 계정을 새로 만들지 않습니다.')
    return json.loads(path.read_text())

def request(path, token=None, payload=None):
    global rate_remaining
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    req = urllib.request.Request(API + path, data=None if payload is None else json.dumps(payload).encode(), headers=headers)
    for attempt in range(3):
        try:
            with urllib.request.urlopen(req, timeout=15) as response:
                remaining = response.headers.get('X-RateLimit-Remaining')
                rate_remaining = int(remaining) if remaining is not None else None
                return json.load(response)['data']
        except urllib.error.HTTPError as error:
            if error.code != 429 or attempt == 2: raise
            seconds = max(1, min(60, int(error.headers.get('Retry-After', '60'))))
            print(f'API 요청 제한: {seconds}초 뒤 조회를 다시 시도합니다.', flush=True)
            time.sleep(seconds)

def wait_browser_capacity(minimum):
    if rate_remaining is not None and rate_remaining < minimum:
        print('다음 브라우저 검증을 위해 API 요청 제한 창이 갱신될 때까지 60초 대기합니다.', flush=True)
        time.sleep(60)

def login():
    case = fixture()
    return request('/api/auth/login', payload={key: case[key] for key in ('email', 'password')})

def verify_settlement(receipts, members, settlement):
    assert settlement['ready'], f"정산 입력 확인 필요: {settlement['issues']}"
    expected = {member['memberId']: {'paid': 0, 'minimum': 0, 'maximum': 0} for member in members}
    total = 0
    for receipt in receipts:
        for item in receipt['items']:
            if item['excludedFromSettlement']: continue
            amount = item['price'] * item['quantity']
            ids = item['memberIds']
            assert ids and len(ids) == len(set(ids)) and all(i in expected for i in ids), '품목 참여자 오류'
            assert receipt['payerMemberId'] in expected, '영수증 결제자 오류'
            expected[receipt['payerMemberId']]['paid'] += amount
            quotient, remainder = divmod(amount, len(ids))
            for member_id in ids:
                expected[member_id]['minimum'] += quotient
                expected[member_id]['maximum'] += quotient + (1 if remainder else 0)
            total += amount
    rows = settlement['members']
    assert len(rows) == len(expected) and {r['memberId'] for r in rows} == set(expected), '정산 멤버 누락/중복'
    for row in rows:
        value = expected[row['memberId']]
        assert row['paid'] == value['paid'], '품목 기준 결제액 불일치'
        assert value['minimum'] <= row['burden'] <= value['maximum'], '참여자 배정 기준 부담액 불일치'
        assert row['net'] == row['paid'] - row['burden'], '멤버 차액 불일치'
    assert sum(r['paid'] for r in rows) == sum(r['burden'] for r in rows) == total, '전체 정산 합계 불일치'
    assert sum(r['net'] for r in rows) == 0, '받을 돈과 보낼 돈 불일치'
    return total

def check():
    case = fixture()
    auth = login()
    token = auth['accessToken']
    base = f"/api/rooms/{case['roomId']}"
    members = request(base + '/members', token)['members']
    receipt = request(base + f"/receipts/{case['receiptId']}", token)
    job = request(base + f"/receipts/{case['receiptId']}/ocr/jobs/latest", token)
    images = request(base + f"/receipts/{case['receiptId']}/images", token)
    settlement = request(base + '/settlements', token)
    assert members, '참여자가 없습니다.'
    assert receipt['items'], '보존된 OCR 영수증에 품목이 없습니다.'
    assert job['jobId'] == case['jobId'] and job['draft']['status'] == 'APPLIED'
    assert sum(row['attemptCount'] for row in job['images']) == case['ocrAttempts'], 'OCR 호출 횟수가 달라졌습니다.'
    summaries = request(base + '/receipts', token)
    receipts = [receipt if row['receiptId'] == receipt['receiptId'] else
                request(base + f"/receipts/{row['receiptId']}", token) for row in summaries]
    total = verify_settlement(receipts, members, settlement)
    assert len(images) == 1
    with urllib.request.urlopen(API + images[0]['url'], timeout=15) as response:
        assert hashlib.sha256(response.read()).hexdigest() == case['imageSha256'], '사진 원본이 달라졌습니다.'
    print(f'PASS: 기존 계정 로그인 / OCR APPLIED / 원본 사진 / 영수증 {len(receipts)}개 / 현재 품목 기준 합계 {total:,}원 정산 일치. OCR 추가 호출 없음.')
    return auth

def browser_state():
    auth = check()
    case = fixture()
    state = {'cookies': [], 'origins': [{'origin': WEB, 'localStorage': [
        {'name': 'itemsplit.auth', 'value': json.dumps(auth)},
        {'name': 'itemsplit.rooms', 'value': json.dumps([{'roomId': case['roomId'], 'roomName': case['roomName'], 'owner': True}])},
        {'name': 'itemsplit.activeRoomId', 'value': str(case['roomId'])}
    ]}]}
    path = STATE / 'browser-state.json'
    path.write_text(json.dumps(state)); path.chmod(0o600)
    return path

def backup():
    directory = STATE / 'backups' / time.strftime('%Y%m%d-%H%M%S')
    directory.mkdir(parents=True, exist_ok=False)
    with (directory / 'database.dump').open('wb') as output:
        run(['docker', 'exec', CONTAINER, 'pg_dump', '-U', 'itemsplit', '-d', 'itemsplit', '-Fc'], stdout=output)
    with tarfile.open(directory / 'storage.tar.gz', 'w:gz') as archive:
        archive.add(STATE / 'storage', arcname='storage')
    shutil.copy2(STATE / 'fixture.json', directory / 'fixture.json')
    print('백업:', directory.relative_to(ROOT))

def alive(pid):
    try: os.kill(pid, 0); return True
    except ProcessLookupError: return False

def stop_process(name):
    file = STATE / (name + '.pid')
    if not file.exists(): return
    pid = int(file.read_text())
    if alive(pid):
        command = subprocess.run(['ps', '-p', str(pid), '-o', 'command='], capture_output=True, text=True).stdout
        expected = str(ROOT / ('build/libs' if name == 'backend' else 'frontend'))
        if expected not in command:
            raise RuntimeError(f'{name} PID가 다른 프로세스로 바뀌었습니다. 자동 종료하지 않습니다.')
        os.killpg(pid, signal.SIGTERM)
        for _ in range(50):
            if not alive(pid): break
            time.sleep(.1)
    file.unlink(missing_ok=True)

def port_busy(port):
    with socket.socket() as sock:
        return sock.connect_ex(('127.0.0.1', port)) == 0

def start_process(name, command, env):
    with (STATE / (name + '.log')).open('ab') as log:
        process = subprocess.Popen(command, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    (STATE / (name + '.pid')).write_text(str(process.pid))

def wait_url(url):
    for _ in range(60):
        try:
            with urllib.request.urlopen(url, timeout=1) as response:
                if response.status == 200: return
        except (urllib.error.URLError, TimeoutError): pass
        time.sleep(.5)
    raise RuntimeError('서버가 준비되지 않았습니다. .local/e2e/*.log를 확인하세요.')

def up():
    fixture()
    run(['docker', 'volume', 'inspect', VOLUME], stdout=subprocess.DEVNULL)
    run(COMPOSE + ['up', '-d', '--wait', 'postgres'])
    stop_process('backend'); stop_process('frontend')
    if port_busy(18080) or port_busy(5173):
        raise RuntimeError('18080 또는 5173 포트를 다른 서버가 사용 중입니다. 해당 개발 서버를 종료 후 재실행하세요.')
    env = os.environ.copy()
    java_home = env.get('JAVA_HOME')
    if sys.platform == 'darwin':
        probe = subprocess.run(['/usr/libexec/java_home', '-v', '21'], capture_output=True, text=True)
        if probe.returncode == 0: java_home = probe.stdout.strip()
    if java_home: env['JAVA_HOME'] = java_home
    java = str(Path(java_home) / 'bin/java') if java_home else 'java'
    key_file = STATE / 'image-signing.key'
    if not key_file.exists(): key_file.write_text(secrets.token_hex(32)); key_file.chmod(0o600)
    env.update(DB_HOST='127.0.0.1', DB_PORT='15432', DB_NAME='itemsplit', DB_USER='itemsplit', DB_PASSWORD='itemsplit',
               STORAGE_TYPE='local', LOCAL_STORAGE_ROOT=str(STATE / 'storage'), LOCAL_STORAGE_SIGNING_SECRET=key_file.read_text().strip())
    run(['./gradlew', 'bootJar', '--console=plain'], env=env)
    if not (ROOT / 'frontend/node_modules').exists(): run(['npm', '--prefix', 'frontend', 'ci'])
    jars = [path for path in (ROOT / 'build/libs').glob('*.jar') if not path.name.endswith('-plain.jar')]
    jar = max(jars, key=lambda path: path.stat().st_mtime)
    start_process('backend', [java, '-jar', str(jar), '--spring.profiles.active=local,ocr', '--server.port=18080', '--server.address=127.0.0.1'], env)
    env['VITE_API_PROXY_TARGET'] = API
    start_process('frontend', ['node', str(ROOT / 'frontend/node_modules/vite/bin/vite.js'), str(ROOT / 'frontend'), '--host', '127.0.0.1', '--port', '5173', '--strictPort'], env)
    wait_url(API + '/actuator/health'); wait_url(WEB)
    check()
    print('준비 완료:', WEB, '\n계정 정보: .local/e2e/fixture.json\n자동 로그인·화면 검증: ./scripts/e2e.sh test')

def browser_cli(session):
    return ['npx', '--yes', '--package', '@playwright/cli', 'playwright-cli', '--session', session]

def run_browser_script(cli, script):
    result = subprocess.run(cli + ['run-code', (ROOT / 'scripts/e2e' / script).read_text()], cwd=ROOT, capture_output=True, text=True)
    print(result.stdout)
    if result.stderr: print(result.stderr, file=sys.stderr)
    if result.returncode != 0 or '### Error' in result.stdout or '### Result\n"PASS:' not in result.stdout:
        raise RuntimeError(f'{script} 브라우저 검증에 실패했습니다.')

def open_preview(state=None):
    state = state or browser_state()
    wait_browser_capacity(60)
    cli = browser_cli('itemsplit-preserved')
    # Never leave the responsive tests' emulated viewport in the window the user operates.
    run(cli + ['close'])
    run(cli + ['open', 'about:blank', '--headed', '--config', str(ROOT / 'scripts/e2e/browser.config.json')])
    run(cli + ['state-load', str(state)])
    run(cli + ['goto', WEB])
    print('실제 Chrome 창 크기를 사용하는 화면:', WEB)

def browser_test(items=False):
    state = browser_state()
    wait_browser_capacity(110)
    (STATE / 'screens').mkdir(exist_ok=True)
    cli = browser_cli('itemsplit-e2e-tests')
    scripts = ['browser-item-check.js', 'browser-item-errors.js'] if items else ['browser-layout-check.js', 'browser-check.js', 'browser-review-check.js']
    try:
        run(cli + ['open', 'about:blank', '--headed'])
        run(cli + ['state-load', str(state)])
        run(cli + ['goto', WEB])
        for script in scripts:
            run_browser_script(cli, script)
    finally:
        run(cli + ['close'])
    check()
    open_preview(state)
    run_browser_script(browser_cli('itemsplit-preserved'), 'browser-native-check.js')

def main():
    os.umask(0o077)
    STATE.mkdir(parents=True, exist_ok=True)
    command = sys.argv[1] if len(sys.argv) > 1 else 'help'
    if command == 'up': up()
    elif command == 'check': check()
    elif command == 'test': browser_test()
    elif command == 'test-items': browser_test(items=True)
    elif command == 'open': open_preview()
    elif command == 'backup': backup()
    elif command == 'down':
        stop_process('backend'); stop_process('frontend'); run(COMPOSE + ['stop'])
        print('서버만 종료했습니다. DB 볼륨·사진·계정·OCR 결과는 보존됩니다.')
    elif command == 'status':
        run(COMPOSE + ['ps']); print('backend:', port_busy(18080), 'frontend:', port_busy(5173))
    else: print('사용법: ./scripts/e2e.sh up|down|status|check|open|test|test-items|backup')

if __name__ == '__main__':
    try: main()
    except subprocess.CalledProcessError as error:
        print('E2E 명령 실패 (exit code ' + str(error.returncode) + '). 위 진단 내용을 확인하세요.', file=sys.stderr); sys.exit(1)
    except (RuntimeError, AssertionError, urllib.error.URLError) as error:
        print('E2E 실행 실패:', str(error), file=sys.stderr); sys.exit(1)
