#!/usr/bin/env python3
"""실제 이미지·DB를 변경하지 않고 배포 스크립트의 Docker 호출과 중단 경계를 검증한다."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SERVICES = ['api-gateway', 'order-service', 'payment-service', 'shipping-service',
            'notification-service', 'user-service', 'product-service', 'settlement-service', 'inventory-service']

class DeploymentScriptsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='commerce-deployment-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for script in ['build-and-push.sh', 'build-and-push-one.sh', 'pull-and-start.sh', 'pull-and-restart-one.sh']:
            shutil.copy2(ROOT / script, self.root / script)
        (self.root / 'scripts').mkdir()
        shutil.copy2(ROOT / 'scripts/init-redis-cluster.sh', self.root / 'scripts/init-redis-cluster.sh')
        for service in SERVICES:
            (self.root / service).mkdir()
            (self.root / service / 'Dockerfile').touch()
        self.env_file = self.root / '.env'
        self.env_file.write_text('JWT_SECRET=existing-test-jwt\nGMAIL_USERNAME=test@example.invalid\nGMAIL_APP_PASSWORD=test-only\nINVENTORY_SERVICE_TOKEN=""\n')
        self.bin = self.root / 'bin'; self.bin.mkdir()
        docker = self.bin / 'docker'
        docker.write_text('''#!/usr/bin/env python3
import json,os,sys
args=sys.argv[1:]
with open(os.environ['MOCK_DOCKER_LOG'],'a') as log: log.write(json.dumps(args)+'\\n')
if ' '.join(args)==os.environ.get('MOCK_FAIL'): sys.exit(19)
if args[:1]==['inspect']: print('sha256:test-image')
if 'psql' in args:
 with open(os.environ['MOCK_SQL'],'w') as output: output.write(sys.stdin.read())
if 'cluster' in args and 'info' in args: print('cluster_state:ok')
''')
        docker.chmod(0o755)
        sleep = self.bin / 'sleep'; sleep.write_text('#!/bin/sh\nexit 0\n'); sleep.chmod(0o755)
        self.log = self.root / 'docker.jsonl'
        self.env = os.environ.copy()
        for key in ['JWT_SECRET', 'INVENTORY_SERVICE_TOKEN']:
            self.env.pop(key, None)
        self.env.update(PATH=str(self.bin) + os.pathsep + self.env['PATH'],
                        MOCK_DOCKER_LOG=str(self.log), MOCK_SQL=str(self.root / 'db.sql'))

    def run_script(self, script, *args, fail=None):
        env = self.env.copy()
        if fail: env['MOCK_FAIL'] = fail
        return subprocess.run(['bash', str(self.root / script), *args], cwd=self.bin, env=env,
                              input='', text=True, capture_output=True, timeout=10)

    def calls(self):
        return [json.loads(line) for line in self.log.read_text().splitlines()] if self.log.exists() else []

    def test_all_builds_and_pushes_nine_images_from_project_root(self):
        result = self.run_script('build-and-push.sh')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(SERVICES, [call[2].split('/')[0] for call in self.calls() if call[0] == 'build'])
        self.assertEqual([f'jybeomss1/{s}:latest' for s in SERVICES], [c[1] for c in self.calls() if c[0] == 'push'])

    def test_build_failure_stops_before_any_push(self):
        result = self.run_script('build-and-push.sh', fail='build -f inventory-service/Dockerfile -t jybeomss1/inventory-service:latest .')
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(any(c[0] == 'push' for c in self.calls()))

    def test_single_inventory_build_and_restart(self):
        self.assertEqual(0, self.run_script('build-and-push-one.sh', 'inventory-service').returncode)
        self.assertEqual(0, self.run_script('pull-and-restart-one.sh', 'inventory-service').returncode)
        self.assertIn(['compose','up','-d','--no-deps','--no-build','--force-recreate','inventory-service'], self.calls())
        self.assertFalse(any(c[:2] == ['rm', '-f'] for c in self.calls()))
        self.assertNotEqual(0, self.run_script('build-and-push-one.sh', 'invalid').returncode)
        self.assertNotEqual(0, self.run_script('pull-and-restart-one.sh', 'invalid').returncode)

    def test_all_pull_provisions_inventory_and_preserves_secrets_on_rerun(self):
        self.assertEqual(0, self.run_script('pull-and-start.sh').returncode)
        contents = self.env_file.read_text()
        self.assertIn('JWT_SECRET=existing-test-jwt', contents)
        token = next(line.split('=',1)[1] for line in contents.splitlines() if line.startswith('INVENTORY_SERVICE_TOKEN='))
        self.assertGreater(len(token), 60)
        self.assertEqual([f'jybeomss1/{s}:latest' for s in SERVICES], [c[1] for c in self.calls() if c[0] == 'pull'])
        calls = self.calls()
        db = next(i for i,c in enumerate(calls) if 'psql' in c)
        redis = next(i for i,c in enumerate(calls) if 'cluster' in c)
        self.assertLess(db, redis)
        self.assertLess(redis, calls.index(['compose','up','-d','--no-build']))
        sql = (self.root / 'db.sql').read_text()
        self.assertIn('CREATE DATABASE inventory_service', sql)
        self.assertIn('WHERE NOT EXISTS', sql)
        self.assertNotIn('DROP', sql)
        self.assertEqual(0, self.run_script('pull-and-start.sh').returncode)
        self.assertEqual(contents, self.env_file.read_text())

    def test_new_environment_gets_both_secrets(self):
        self.env_file.unlink()
        self.assertEqual(0, self.run_script('pull-and-start.sh').returncode)
        contents = self.env_file.read_text()
        self.assertIn('JWT_SECRET=', contents)
        self.assertIn('INVENTORY_SERVICE_TOKEN=', contents)
        self.assertEqual(0o600, self.env_file.stat().st_mode & 0o777)

    def test_pull_or_database_failure_stops_before_application_start(self):
        for fail in ['pull jybeomss1/inventory-service:latest',
                     'compose exec -T postgres psql -U postgres -d postgres -v ON_ERROR_STOP=1',
                     'compose config --quiet']:
            with self.subTest(fail=fail):
                if self.log.exists(): self.log.unlink()
                self.assertNotEqual(0, self.run_script('pull-and-start.sh', fail=fail).returncode)
                self.assertNotIn(['compose','up','-d','--no-build'], self.calls())

if __name__ == '__main__':
    unittest.main()
