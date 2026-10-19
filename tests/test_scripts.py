"""Exercise deployment failure paths without a TV, SDK, or real ADB."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
STOCK = 'com.heytap.tv.launcher/com.oneplus.tv.launcher2.HomeLauncher'
HOME = 'com.gathin.tvhome/.MainActivity'

FAKE_ADB = r'''#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
p = Path(os.environ['FAKE_STATE'])
s = json.loads(p.read_text())
a = sys.argv[3:]
s['calls'].append(a)
def save(): p.write_text(json.dumps(s))
if a == ['get-state']: print('device')
elif a and a[0] == 'push':
    if s.get('push_fail'): save(); sys.exit(1)
elif a and a[0] in ('pull', 'uninstall'): pass
elif a and a[0] == 'shell':
    a = a[1:]
    if a[:1] == ['getprop']:
        print('test-device' if a[1] == 'ro.serialno' else '1')
    elif a[:3] == ['pm', 'list', 'packages']:
        enabled = a[3] == '-e'
        for pkg, value in s['packages'].items():
            if value == enabled: print('package:' + pkg)
    elif a[:2] in (['pm', 'enable'], ['pm', 'disable-user']):
        pkg = a[-1]
        if s.get('enable_fail') and a[1] == 'enable':
            print('Error: simulated failure'); save(); sys.exit(0)
        s['packages'][pkg] = a[1] == 'enable'
        print('Package ' + pkg + ' new state: ' + ('enabled' if s['packages'][pkg] else 'disabled-user'))
    elif a[:2] == ['pm', 'install']:
        print('Failure [INSTALL_FAILED_TEST]' if s.get('install_fail') else 'Success')
    elif a[:3] == ['cmd', 'package', 'resolve-activity']: print(s['home'])
    elif a[:3] == ['cmd', 'package', 'set-home-activity']:
        if not (s.get('switch_fail') and a[-1].startswith('com.gathin.tvhome/')): s['home'] = a[-1]
        print('Success')
    elif a[:2] == ['am', 'start']:
        if s.get('start_fail') and a[-1].startswith('com.gathin.tvhome/'):
            print('Error: unable to start')
        else: print('Status: ok')
    elif a[:1] == ['screencap']: pass
    elif a[:2] == ['rm', '-f']: pass
    elif a[:2] == ['pm', 'grant']: pass
    elif a[:3] == ['cmd', 'package', 'compile']: print('Success')
    else: save(); sys.exit('Unexpected shell: ' + repr(a))
else: save(); sys.exit('Unexpected adb: ' + repr(a))
save()
'''


class DeploymentTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        for name in ('build-install.sh', 'disable-tv.sh', 'restore.sh', 'scripts/tv-common.sh'):
            target = self.root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / name, target)
        app = self.root / 'launcher/app'
        app.mkdir(parents=True)
        (app / 'debug.keystore').touch()
        gradle = self.root / 'launcher/gradlew'
        gradle.write_text('#!/bin/sh\nmkdir -p app/build/outputs/apk/release\ntouch app/build/outputs/apk/release/app-release.apk\n')
        gradle.chmod(0o755)
        adb = self.root / 'adb'
        adb.write_text(FAKE_ADB)
        adb.chmod(0o755)
        self.state = self.root / 'state.json'
        self.state.write_text(json.dumps({'home': STOCK, 'packages': {
            STOCK.split('/')[0]: True, 'test.enabled': True, 'test.disabled': False,
        }, 'calls': []}))
        self.env = dict(os.environ, ADB=str(adb), FAKE_STATE=str(self.state),
                        STATE_ROOT=str(self.root / 'records'))
        self.list = self.root / 'debloat.txt'
        self.list.write_text('# comment\ntest.enabled # inline\ntest.disabled\ntest.missing\n')

    def run_script(self, name, *args, succeeds=True):
        result = subprocess.run(['bash', str(self.root / name), *map(str, args)],
                                env=self.env, capture_output=True, text=True)
        if succeeds:
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        else:
            self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        return result

    def data(self):
        return json.loads(self.state.read_text())

    def flag(self, name):
        data = self.data()
        data[name] = True
        self.state.write_text(json.dumps(data))

    def test_restore_preserves_previously_disabled_packages(self):
        self.run_script('disable-tv.sh', self.list)
        self.run_script('disable-tv.sh', self.list)  # Repeated runs retain original journal.
        self.run_script('restore.sh')
        self.assertTrue(self.data()['packages']['test.enabled'])
        self.assertFalse(self.data()['packages']['test.disabled'])
        self.assertNotIn('test.missing', self.data()['packages'])

    def test_install_failure_does_not_disable_stock(self):
        self.flag('install_fail')
        self.run_script('build-install.sh', succeeds=False)
        self.assertEqual(self.data()['home'], STOCK)
        self.assertTrue(self.data()['packages'][STOCK.split('/')[0]])
        self.assertFalse(any('disable-user' in call for call in self.data()['calls']))

    def test_failed_upload_never_installs_even_if_remote_apk_exists(self):
        self.flag('push_fail')
        self.run_script('build-install.sh', succeeds=False)
        self.assertFalse(any(call[:3] == ['shell', 'pm', 'install']
                             for call in self.data()['calls']))
        self.assertFalse(any('disable-user' in call for call in self.data()['calls']))

    def test_start_failure_does_not_disable_stock(self):
        self.flag('start_fail')
        self.run_script('build-install.sh', succeeds=False)
        self.assertFalse(any('disable-user' in call for call in self.data()['calls']))

    def test_failed_home_check_rolls_back(self):
        self.flag('switch_fail')
        self.run_script('build-install.sh', succeeds=False)
        self.assertEqual(self.data()['home'], STOCK)
        self.assertTrue(self.data()['packages'][STOCK.split('/')[0]])

    def test_install_and_restore_home(self):
        self.run_script('build-install.sh')
        self.assertEqual(self.data()['home'], HOME)
        self.assertFalse(self.data()['packages'][STOCK.split('/')[0]])
        self.run_script('restore.sh')
        self.assertEqual(self.data()['home'], STOCK)
        self.assertTrue(self.data()['packages'][STOCK.split('/')[0]])

    def test_restore_failure_keeps_journal_for_retry(self):
        self.run_script('disable-tv.sh', self.list)
        self.flag('enable_fail')
        self.run_script('restore.sh', succeeds=False)
        journal = next((self.root / 'records').glob('*/debloat.txt'))
        self.assertIn('test.enabled', journal.read_text())
        data = self.data()
        del data['enable_fail']
        self.state.write_text(json.dumps(data))
        self.run_script('restore.sh')
        self.assertTrue(self.data()['packages']['test.enabled'])
        self.assertEqual(journal.read_text(), '')

    def test_unknown_home_is_not_disabled(self):
        data = self.data()
        data['home'] = 'other.launcher/.Home'
        self.state.write_text(json.dumps(data))
        self.run_script('build-install.sh', succeeds=False)
        self.assertFalse(any('disable-user' in call for call in self.data()['calls']))

    def test_invalid_package_is_rejected_before_adb(self):
        self.list.write_text('test.bad;reboot\n')
        self.run_script('disable-tv.sh', self.list, succeeds=False)
        self.assertEqual(self.data()['calls'], [])


if __name__ == '__main__':
    unittest.main()
