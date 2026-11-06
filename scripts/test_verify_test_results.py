import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('verify_test_results', Path(__file__).with_name('verify-test-results.py'))
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)


class TestReportVerification(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / 'settings.gradle.kts').write_text('include(":probe:application")')
        self.module = self.root / 'probe/application'
        source = self.module / 'src/test/kotlin/PostgresTest.kt'
        source.parent.mkdir(parents=True)
        source.write_text('package probe\n@EnabledIfEnvironmentVariable(named = "PROBE_TEST_JDBC_URL", matches = ".+")\nclass PostgresTest')
        self.report = self.module / 'build/test-results/test/TEST-probe.PostgresTest.xml'
        self.report.parent.mkdir(parents=True)

    def write_report(self, name='probe.PostgresTest', tests=2, skipped=0, failures=0):
        self.report.write_text(f'<testsuite name="{name}" tests="{tests}" skipped="{skipped}" failures="{failures}" errors="0"/>')

    def test_requires_reports_for_modules_with_test_sources(self):
        with self.assertRaisesRegex(ValueError, 'Missing test reports'):
            verifier.verify(self.root, True)

    def test_rejects_skipped_postgres_tests(self):
        self.write_report(skipped=2)
        with self.assertRaisesRegex(ValueError, 'Skipped test suite'):
            verifier.verify(self.root, True)

    def test_rejects_missing_postgres_suite_even_when_other_tests_pass(self):
        self.write_report(name='probe.OtherTest')
        with self.assertRaisesRegex(ValueError, 'PostgreSQL regression suite did not execute'):
            verifier.verify(self.root, True)

    def test_rejects_failed_tests(self):
        self.write_report(failures=1)
        with self.assertRaisesRegex(ValueError, 'Failed test suite'):
            verifier.verify(self.root, True)

    def test_rejects_empty_execution(self):
        self.write_report(tests=0)
        with self.assertRaisesRegex(ValueError, 'No tests executed'):
            verifier.verify(self.root, True)

    def test_accepts_executed_postgres_tests_and_ignores_nested_checkouts(self):
        self.write_report()
        unrelated = self.root / 'app/build/checkouts/other/build/test-results/test/TEST-other.xml'
        unrelated.parent.mkdir(parents=True)
        unrelated.write_text('<testsuite name="unrelated" tests="8" failures="8"/>')
        self.assertEqual(dict(modules=1, tests=2, failures=0, errors=0, skipped=0, postgresSuites=1, postgresTests=2),
            verifier.verify(self.root, True))


if __name__ == '__main__':
    unittest.main()
