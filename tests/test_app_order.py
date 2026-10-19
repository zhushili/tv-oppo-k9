"""Verify launch-frequency ranking and system-tool exclusion with the production rules."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class AppOrderTests(unittest.TestCase):
    def test_usage_order_and_filter(self):
        java_home = Path(os.environ.get('JAVA_HOME', '/opt/homebrew/opt/openjdk@17'))
        javac = str(java_home / 'bin/javac') if java_home.exists() else shutil.which('javac')
        java = str(java_home / 'bin/java') if java_home.exists() else shutil.which('java')
        with tempfile.TemporaryDirectory() as output:
            subprocess.run([javac, '-d', output,
                            str(ROOT / 'launcher/app/src/main/java/com/gathin/tvhome/AppOrder.java'),
                            str(ROOT / 'tests/java/AppOrderTest.java')], check=True)
            subprocess.run([java, '-cp', output, 'com.gathin.tvhome.AppOrderTest'], check=True)
