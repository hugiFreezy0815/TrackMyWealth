"""#643: mobile/scripts/gradle-retry.sh repeats the Android build only when it failed on a download.

The script runs as in Mobile Native CI, with a stand-in for ./gradlew that answers each call from a
list and counts the calls, and without the pauses between attempts.
"""

import os
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[2] / "mobile" / "scripts" / "gradle-retry.sh"

DOWNLOAD_FAILURE = (
    "* What went wrong:\n"
    "   > Could not resolve all artifacts for configuration 'classpath'.\n"
    "      > Could not find org.jetbrains.kotlin:kotlin-stdlib:2.3.0.\n"
)
COMPILE_FAILURE = "* What went wrong:\nExecution failed for task ':app:compileReleaseKotlin'.\n"


class GradleRetryTest(unittest.TestCase):
    def run_with(self, *answers):
        """Runs the script; the stand-in answers call N with answers[N] = (exit status, output)."""
        with tempfile.TemporaryDirectory() as work:
            work_dir = Path(work)
            for index, (status, output) in enumerate(answers):
                (work_dir / f"answer{index}").write_text(f"{status}\n{output}", encoding="utf-8")
            stand_in = work_dir / "gradlew"
            stand_in.write_text(
                "#!/bin/sh\n"
                f'dir="{work_dir}"\n'
                'n=$(cat "$dir/calls" 2>/dev/null || echo 0)\n'
                'echo $((n + 1)) > "$dir/calls"\n'
                'echo "args: $*" >> "$dir/args"\n'
                'tail -n +2 "$dir/answer$n"\n'
                'exit "$(head -n 1 "$dir/answer$n")"\n',
                encoding="utf-8",
            )
            stand_in.chmod(0o755)
            result = subprocess.run(
                ["sh", str(SCRIPT), "assembleRelease", "--no-daemon"],
                env={**os.environ, "GRADLEW": str(stand_in), "RETRY_PAUSE": "0"},
                capture_output=True,
                text=True,
                check=False,
            )
            calls = int((work_dir / "calls").read_text())
            args = (work_dir / "args").read_text().splitlines()
        return result, calls, args

    def test_a_build_that_passes_runs_once_with_the_given_arguments(self):
        result, calls, args = self.run_with((0, "BUILD SUCCESSFUL\n"))

        self.assertEqual(result.returncode, 0)
        self.assertEqual(calls, 1)
        self.assertEqual(args, ["args: assembleRelease --no-daemon"])
        self.assertIn("BUILD SUCCESSFUL", result.stdout)

    def test_a_download_failure_is_retried_until_the_build_passes(self):
        result, calls, _ = self.run_with(
            (1, DOWNLOAD_FAILURE), (1, "Received status code 429 from server\n"), (0, "OK\n")
        )

        self.assertEqual(result.returncode, 0)
        self.assertEqual(calls, 3)
        self.assertIn("attempt 2 of 3", result.stderr)

    def test_three_download_failures_give_up_with_gradles_status(self):
        result, calls, _ = self.run_with(*[(1, DOWNLOAD_FAILURE)] * 3)

        self.assertEqual(result.returncode, 1)
        self.assertEqual(calls, 3)
        self.assertIn("failed on a download 3 times", result.stderr)

    def test_a_failure_that_is_no_download_is_not_retried(self):
        result, calls, _ = self.run_with((2, COMPILE_FAILURE), (0, "never reached\n"))

        self.assertEqual(result.returncode, 2)
        self.assertEqual(calls, 1)
        self.assertIn("not on a download", result.stderr)

    def test_a_broken_gradle_distribution_download_is_retried(self):
        result, calls, _ = self.run_with(
            (1, "Downloading https://services.gradle.org/distributions/gradle-9.4.1-bin.zip\n"
                "Exception in thread \"main\" java.net.SocketException: Connection reset\n"),
            (0, "OK\n"),
        )

        self.assertEqual(result.returncode, 0)
        self.assertEqual(calls, 2)


if __name__ == "__main__":
    unittest.main()
