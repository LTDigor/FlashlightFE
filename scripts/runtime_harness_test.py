import unittest

from test_release_runtime import verify_log


class RuntimeHarnessTests(unittest.TestCase):
    def test_requires_every_stage_and_the_correct_dependency_mode(self):
        complete = '\n'.join(f'{marker} curios=false' for marker in (
            'RUNTIME_CHECK_STARTED', 'RUNTIME_CHECK_STOPPING_OK', 'RUNTIME_CHECK_PASS'))
        verify_log(complete, False)
        for line in complete.splitlines():
            with self.subTest(missing=line), self.assertRaises(AssertionError):
                verify_log(complete.replace(line, ''), False)
        with self.assertRaises(AssertionError):
            verify_log(complete, True)

    def test_crash_after_progress_never_counts_as_a_pass(self):
        text = '\n'.join(f'{marker} curios=true' for marker in (
            'RUNTIME_CHECK_STARTED', 'RUNTIME_CHECK_STOPPING_OK', 'RUNTIME_CHECK_PASS'))
        for failure in ('RUNTIME_CHECK_FAILURE: bad shutdown', 'Encountered an unexpected exception'):
            with self.subTest(failure=failure), self.assertRaises(AssertionError):
                verify_log(text + '\n' + failure, True)
