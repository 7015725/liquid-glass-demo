"""Synthetic checker fixtures; not application test counts."""
import tempfile
import unittest
from pathlib import Path

from test_report import inspect_reports


class ReportGuardTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.addCleanup(self.temp.cleanup)

    def report(self, body='<testcase classname="A" name="case"/>', tests=1,
               failures=0, errors=0, skipped=0):
        (self.root / 'TEST-fixture.xml').write_text(
            f'<testsuite tests="{tests}" failures="{failures}" errors="{errors}" '
            f'skipped="{skipped}">{body}</testsuite>', encoding='utf-8')

    def test_missing_reports_fails(self):
        self.assertTrue(inspect_reports(self.root)['problems'])

    def test_zero_cases_fails(self):
        self.report('', tests=0)
        self.assertTrue(inspect_reports(self.root)['problems'])

    def test_real_success_counts_case(self):
        self.report()
        result = inspect_reports(self.root)
        self.assertEqual([], result['problems'])
        self.assertEqual(1, result['tests'])
        self.assertEqual(1, result['passed'])

    def test_leaf_failure_is_not_treated_as_absent(self):
        self.report('<testcase classname="A" name="case"><failure/></testcase>', failures=1)
        result = inspect_reports(self.root)
        self.assertEqual(1, result['failures'])
        self.assertTrue(result['problems'])

    def test_summary_mismatch_fails(self):
        self.report(tests=2)
        self.assertTrue(inspect_reports(self.root)['problems'])

    def test_duplicate_test_identity_fails(self):
        self.report('<testcase classname="A" name="case"/>' * 2, tests=2)
        self.assertTrue(inspect_reports(self.root)['problems'])

    def test_skipped_case_fails(self):
        self.report('<testcase classname="A" name="case"><skipped/></testcase>', skipped=1)
        self.assertTrue(inspect_reports(self.root)['problems'])

    def test_error_case_fails(self):
        self.report('<testcase classname="A" name="case"><error/></testcase>', errors=1)
        result = inspect_reports(self.root)
        self.assertEqual(1, result['errors'])
        self.assertTrue(result['problems'])

    def test_malformed_xml_fails(self):
        (self.root / 'TEST-fixture.xml').write_text('<invalid')
        self.assertTrue(inspect_reports(self.root)['problems'])


if __name__ == '__main__':
    unittest.main()
