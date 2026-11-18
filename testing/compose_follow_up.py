import argparse
import http.cookiejar
import json
import os
import platform
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'backend/load'))
from profile import Client, PersistentSession, exchange_probe


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--load', action='store_true')
    parser.add_argument('--inspect-isolation', action='store_true')
    parser.add_argument('--image-project')
    args = parser.parse_args()
    subprocess.run(['docker', 'info'], check=True, stdout=subprocess.DEVNULL)
    project = 'tz-follow-up-' + uuid.uuid4().hex[:12]
    environment = os.environ.copy()
    password = uuid.uuid4().hex
    environment.update(APP_DB_PASSWORD=uuid.uuid4().hex, APP_COMMAND_PASSWORD=password)
    config = json.loads(
        subprocess.check_output(
            ['docker', 'compose', 'config', '--format', 'json'],
            cwd=ROOT,
            env=environment,
            text=True,
        ),
    )
    config['name'] = project
    image_metadata = []
    if args.image_project:
        image_names = [args.image_project + '-' + service for service in ['backend', 'emulator', 'frontend']]
        image_metadata = json.loads(
            subprocess.check_output(
                ['docker', 'image', 'inspect', *image_names],
                text=True,
            ),
        )
        for service in ['backend', 'emulator', 'frontend']:
            config['services'][service]['image'] = args.image_project + '-' + service
            config['services'][service].pop('build')
    for network, settings in config.get('networks', {}).items():
        settings['name'] = project + '-' + network
    for volume, settings in config['volumes'].items():
        settings['name'] = project + '-' + volume
    assert all(
        settings['name'].startswith(project + '-') and not settings.get('external')
        for settings in config['networks'].values()
    )
    assert all(
        settings['name'].startswith(project + '-') and not settings.get('external')
        for settings in config['volumes'].values()
    )
    assert all('container_name' not in service for service in config['services'].values())
    for service, port in [('frontend', 80), ('backend', 8080), ('emulator', 8090)]:
        config['services'][service]['ports'] = [{'target': port, 'published': '0', 'host_ip': '127.0.0.1'}]
    if args.inspect_isolation:
        print(
            json.dumps(
                {'project': project, 'networks': config['networks'], 'volumes': config['volumes']},
                indent=2,
            ),
        )
        return
    docker_info = json.loads(
        subprocess.check_output(
            ['docker', 'info', '--format', '{{json .}}'],
            text=True,
        ),
    )
    report = {
        'project': project,
        'mode': 'load' if args.load else 'integration',
        'prebuilt_images': [
            {'id': item['Id'], 'tags': item['RepoTags'], 'created': item['Created']}
            for item in image_metadata
        ],
        'environment': {
            'generator_os': platform.platform(),
            'host_cpu_count': os.cpu_count(),
            'host_architecture': platform.machine(),
            'docker_version': docker_info['ServerVersion'],
            'docker_os': docker_info['OperatingSystem'],
            'docker_cpu_count': docker_info['NCPU'],
            'docker_memory_bytes': docker_info['MemTotal'],
        },
    }
    if platform.system() == 'Darwin':
        report['environment']['host_memory_bytes'] = int(
            subprocess.check_output(
                ['sysctl', '-n', 'hw.memsize'],
                text=True,
            ),
        )
        report['environment']['host_cpu_model'] = subprocess.check_output(
            ['sysctl', '-n', 'machdep.cpu.brand_string'],
            text=True,
        ).strip()
    output = ROOT / 'testing/verification' / (project + '.json')
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=project) as directory:
        config_path = Path(directory) / 'compose.json'
        config_path.write_text(json.dumps(config))
        compose = ['docker', 'compose', '-f', str(config_path), '-p', project]

        def run(*arguments):
            subprocess.run(compose + list(arguments), check=True, env=environment)

        def address(service, port):
            return 'http://' + subprocess.check_output(
                compose + ['port', service, str(port)],
                text=True,
            ).strip()

        try:
            run(
                'up',
                '-d',
                '--no-build' if args.image_project else '--build',
                '--wait',
                '--wait-timeout',
                '240',
            )
            run('stop', 'frontend', 'backend')
            run(
                'run',
                '--rm',
                '--no-deps',
                '-e',
                'APP_EXCHANGE_ENABLED=false',
                'backend',
                '--server.port=0',
                '--app.command=bootstrap',
                '--app.worker-one-url=http://emulator:8090/workers/worker-1',
                '--app.worker-two-url=http://emulator:8090/workers/worker-2',
            )
            if args.load:
                run(
                    'run',
                    '--rm',
                    '--no-deps',
                    '-e',
                    'APP_EXCHANGE_ENABLED=false',
                    'backend',
                    '--server.port=0',
                    '--app.command=load-data',
                    '--app.username=initiator1',
                )
            run('up', '-d', '--wait')
            url = address('frontend', 80)
            browser_cookies = http.cookiejar.CookieJar()
            browser = urllib.request.build_opener(
                urllib.request.ProxyHandler({}),
                urllib.request.HTTPCookieProcessor(browser_cookies),
            )
            for path in ['/', '/tasks/direct-route']:
                with browser.open(url + path) as response:
                    assert response.status == 200
                    assert b'<html' in response.read()
            report['index_and_spa_route'] = 'PASS'
            try:
                browser.open(url + '/api/me')
                raise AssertionError('Anonymous /api/me must reject')
            except urllib.error.HTTPError as response:
                assert response.code == 401
                assert response.headers.get('X-CSRF-TOKEN')
                assert any(cookie.name == 'SESSION' for cookie in browser_cookies)
            missing_csrf = urllib.request.Request(
                url + '/api/session',
                data=json.dumps({'username': 'initiator1', 'password': password}).encode(),
                headers={'Content-Type': 'application/json'},
                method='POST',
            )
            try:
                browser.open(missing_csrf)
                raise AssertionError('Login without CSRF must reject')
            except urllib.error.HTTPError as response:
                assert response.code == 403
            report['anonymous_cookie_csrf_rejection'] = 'PASS'
            client = Client(url, 'initiator1', password)
            status, listing = client.request('GET', '/api/tasks?page=0&size=20')
            report['initial_list'] = listing
            assert status == 200
            ids = subprocess.check_output(compose + ['ps', '-q'], text=True).split()
            inspection = json.loads(subprocess.check_output(['docker', 'inspect', *ids], text=True))
            report['limits'] = [
                {
                    'service': item['Config']['Labels']['com.docker.compose.service'],
                    'cpu': item['HostConfig']['NanoCpus'],
                    'memory': item['HostConfig']['Memory'],
                    'health': item['State']['Health']['Status'],
                }
                for item in inspection
            ]
            assert len(inspection) == 5
            assert {item['service'] for item in report['limits']} == set(config['services'])
            assert all(item['health'] == 'healthy' for item in report['limits'])
            assert sum(item['cpu'] for item in report['limits']) <= 2_000_000_000
            assert sum(item['memory'] for item in report['limits']) <= 4 * 1024**3
            if args.load:
                assert listing['total'] == 5000
                environment['LOAD_PASSWORD'] = password
                environment['LOAD_OPERATOR_PASSWORD'] = password
                subprocess.run(
                    [
                        sys.executable,
                        str(ROOT / 'backend/load/profile.py'),
                        '--url',
                        url,
                        '--emulator-url',
                        address('emulator', 8090),
                        '--environment',
                        project + ': five containers, 2 CPU / 3456 MiB',
                        '--output',
                        str(output.with_suffix('.load.json')),
                    ],
                    check=True,
                    env=environment,
                )
            else:
                probe = argparse.Namespace(
                    url=url,
                    username='initiator1',
                    operators='operator1,operator2',
                    emulator_url=address('emulator', 8090),
                )
                report['exchange'] = exchange_probe(probe, password)
                assert report['exchange']['status'] == 'observed'
                task_id = str(report['exchange']['task_id'])
                before = client.request('GET', '/api/tasks/' + task_id)
                history_before = client.request('GET', '/api/tasks/' + task_id + '/history')
                external_id = before[1]['externalId']
                with browser.open(
                    address('emulator', 8090) + '/admin/operations/' + external_id,
                ) as response:
                    external_before = json.load(response)
                run('up', '-d', '--wait')
                client.opener.close()
                run('restart')
                run('up', '-d', '--wait')
                client.base_url = address('frontend', 80)
                previous_cookies = client.opener.cookies
                client.opener = PersistentSession(client.base_url)
                client.opener.cookies = previous_cookies
                session_status, session_actor = client.request('GET', '/api/me')
                report['post_restart_session_status'] = session_status
                assert session_status == 200, f'Session after restart: HTTP {session_status}'
                assert session_actor == client.actor, 'Authenticated actor changed after restart'
                assert client.request('GET', '/api/tasks/' + task_id) == before
                assert client.request('GET', '/api/tasks/' + task_id + '/history') == history_before
                with browser.open(
                    address('emulator', 8090) + '/admin/operations/' + external_id,
                ) as response:
                    assert json.load(response) == external_before
                report['restart_history_and_external_operation'] = 'PASS'
                report['restart_session_and_task'] = 'PASS'
            report['status'] = 'PASS'
        except BaseException as error:
            report['status'] = 'FAIL'
            report['error'] = f'{type(error).__name__}: {error}'
            raise
        finally:
            output.write_text(json.dumps(report, indent=2) + '\n')
            run('down', '-v', '--remove-orphans')


if __name__ == '__main__':
    main()
