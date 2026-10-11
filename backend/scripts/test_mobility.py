"""Run the central mobility suite without unrelated legacy visa API probes."""
import sys
import unittest
from pathlib import Path

if __name__ == '__main__':
    root = Path(__file__).resolve().parents[1] / 'tests'
    loader = unittest.TestLoader()
    suite = unittest.TestSuite()
    for file in sorted(root.glob('test_*.py')):
        if file.name != 'test_visaguy_mcp.py':
            suite.addTests(loader.discover(str(root), pattern=file.name))
    result = unittest.TextTestRunner(verbosity=1).run(suite)
    sys.exit(0 if result.wasSuccessful() else 1)
