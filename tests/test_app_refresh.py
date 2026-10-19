"""Compile/run the Android-independent refresh coordinator with JDK 17."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class AppRefreshTests(unittest.TestCase):
    def test_refresh_lifecycle_and_races(self):
        java_home = Path(os.environ.get('JAVA_HOME', '/opt/homebrew/opt/openjdk@17'))
        javac = str(java_home / 'bin/javac') if java_home.exists() else shutil.which('javac')
        java = str(java_home / 'bin/java') if java_home.exists() else shutil.which('java')
        self.assertIsNotNone(javac, 'JDK 17 is required')
        with tempfile.TemporaryDirectory() as output:
            subprocess.run([javac, '-d', output,
                            str(ROOT / 'launcher/app/src/main/java/com/gathin/tvhome/AppRefresh.java'),
                            str(ROOT / 'tests/java/AppRefreshTest.java')], check=True)
            subprocess.run([java, '-cp', output, 'com.gathin.tvhome.AppRefreshTest'], check=True)
