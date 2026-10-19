"""Local-only relay tests. Fake nc echoes bytes; no TV or external network is used."""
import importlib.util
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parents[1]
PROXY = ROOT / 'scripts/tv-proxy.py'
spec = importlib.util.spec_from_file_location('tv_proxy', PROXY)
proxy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(proxy)


class ProxyTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='tv-proxy-test-', dir='/tmp')
        self.root = Path(self.tmp.name)
        self.state = self.root / 'state'
        with socket.socket() as listener:
            listener.bind(('127.0.0.1', 0))
            self.port = listener.getsockname()[1]
        self.nc = self.root / 'nc'
        self.nc.write_text('#!' + sys.executable + '\nimport os\nwhile True:\n data=os.read(0,65536)\n if not data: break\n os.write(1,data)\n')
        self.nc.chmod(0o755)
        self.addCleanup(self.cleanup)

    def cleanup(self):
        proxy.control(self.state / 'control.sock', 'stop')
        for _ in range(60):
            if not (self.state / 'control.sock').exists():
                break
            time.sleep(0.1)
        self.tmp.cleanup()

    def start(self, host='tv.test', succeeds=True):
        result = subprocess.run([sys.executable, str(PROXY), 'start', '--host', host,
                                 '--port', '5555', '--local-port', str(self.port),
                                 '--state', str(self.state), '--nc', str(self.nc)],
                                capture_output=True, text=True, timeout=10)
        self.assertEqual(result.returncode == 0, succeeds, result.stdout + result.stderr)

    def exchange(self):
        payload = b'ADB-test\x00' * 20000
        with socket.create_connection(('127.0.0.1', self.port), timeout=3) as client:
            client.sendall(payload)
            received = bytearray()
            while len(received) < len(payload):
                chunk = client.recv(65536)
                self.assertTrue(chunk)
                received.extend(chunk)
            self.assertEqual(received, payload)

    def test_reconnect_and_reuse(self):
        self.start()
        self.exchange()
        self.start()  # Existing owned relay is reused; unrelated processes are never killed.
        self.exchange()  # Fresh remote process after the previous ADB connection closed.
        self.start(host='another-tv.test')
        self.assertEqual(proxy.control(self.state / 'control.sock', 'status')['host'], 'another-tv.test')
        self.exchange()

    def test_busy_port_fails_without_killing_owner(self):
        with socket.socket() as occupied:
            occupied.bind(('127.0.0.1', self.port))
            occupied.listen(1)
            self.start(succeeds=False)
            with socket.create_connection(('127.0.0.1', self.port), timeout=1):
                client, _ = occupied.accept()
                client.close()

    def test_verify_requires_device_state_and_uses_adb_override(self):
        adb = self.root / 'custom-adb'
        adb.write_text('#!' + sys.executable + '\nimport os,sys\nprint(os.environ["FAKE_DEVICE_STATE"] if "get-state" in sys.argv else "connected")\n')
        adb.chmod(0o755)
        for state, expected in [('unauthorized', 1), ('offline', 1), ('device', 0)]:
            result = subprocess.run([sys.executable, str(PROXY), 'verify', '--state', str(self.state),
                                     '--local-port', str(self.port), '--adb', str(adb), '--attempts', '1'],
                                    env=dict(os.environ, FAKE_DEVICE_STATE=state),
                                    capture_output=True, text=True, timeout=8)
            self.assertEqual(result.returncode, expected, result.stderr)

    def test_connect_script_reports_failure_and_supports_stop(self):
        adb = self.root / 'adb'
        adb.write_text('#!/bin/sh\necho unauthorized\n')
        adb.chmod(0o755)
        self.start()  # Reuse the test echo relay, never connect to a real TV.
        env = dict(os.environ, TV_IP='tv.test', LOCAL_PORT=str(self.port),
                   TV_PROXY_STATE=str(self.state), ADB=str(adb), PYTHON=sys.executable)
        result = subprocess.run(['bash', str(ROOT / 'connect-tv.sh')], env=env,
                                capture_output=True, text=True, timeout=10)
        self.assertNotEqual(result.returncode, 0)
        result = subprocess.run(['bash', str(ROOT / 'connect-tv.sh'), '--stop'], env=env,
                                capture_output=True, text=True, timeout=5)
        self.assertEqual(result.returncode, 0, result.stderr)
