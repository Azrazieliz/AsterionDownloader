"""Offline engine-harness tests: do not contact publishers or build an APK."""
import contextlib
import importlib.util
import io
import json
import pathlib
import subprocess
import unittest
from unittest.mock import patch


PATH = pathlib.Path(__file__).with_name("provider_focus_probe_batch.py")
spec = importlib.util.spec_from_file_location("provider_focus_probe_batch", PATH)
engine = importlib.util.module_from_spec(spec)
spec.loader.exec_module(engine)


class ProviderFocusProbeBatchTest(unittest.TestCase):
    def test_active_case_set_is_exactly_seven_owner_failures(self):
        self.assertEqual({
            "jav_guru", "javideo", "helloporn", "pornhub",
            "hentaihaven", "mp4hentai", "uncensoredhentai",
        }, set(engine.CASES))
        self.assertNotIn("archivebate", engine.CASES)
        self.assertEqual("verify_haven_selected_episode_hls_20261117.py",
                         engine.CASES["hentaihaven"][0])
        self.assertEqual("verify_mp4hentai_upfiles_public_form.py",
                         engine.CASES["mp4hentai"][0])


    def test_one_failed_provider_makes_whole_batch_fail(self):
        cases=[
            {"provider":"jav_guru","exit":0},
            {"provider":"helloporn","exit":6},
            {"provider":"hentaihaven","exit":0},
        ]
        summary=engine.summarize(cases)
        self.assertEqual(1,summary["exit"])
        self.assertEqual(["helloporn"],summary["failed_providers"])
        self.assertEqual(2,summary["probes_completed"])
        self.assertFalse(summary["android_original_receipts_verified"])

    def test_timeout_and_exception_are_not_a_pass(self):
        summary=engine.summarize([
            {"provider":"mp4hentai","status":"timeout"},
            {"provider":"javideo","status":"error","reason_class":"OSError"},
        ])
        self.assertEqual(1,summary["exit"])
        self.assertEqual(2,summary["probes_failed"])

    def test_even_green_diagnostic_is_not_an_original_receipt(self):
        summary=engine.summarize([{"provider":"rule34video","exit":0}])
        self.assertEqual(0,summary["exit"])
        self.assertFalse(summary["android_original_receipts_verified"])
        self.assertEqual("PC selected-provider diagnostics only",
                         summary["verification_scope"])

    def test_real_nonzero_subprocess_exit_propagates_to_main(self):
        cases={
            "provider_a":("a.py",[]),
            "provider_b":("b.py",[]),
        }
        def fake_run(argv,**kwargs):
            code=6 if argv[1].endswith("b.py") else 0
            return subprocess.CompletedProcess(
                argv,code,stdout='{"selected":"probe"}',stderr="")
        out=io.StringIO()
        with patch.object(engine.subprocess,"run",side_effect=fake_run):
            with contextlib.redirect_stdout(out):
                code=engine.main(cases)
        self.assertEqual(1,code)
        emitted=[json.loads(line) for line in out.getvalue().splitlines()]
        self.assertEqual(3,len(emitted))
        self.assertEqual(1,emitted[-1]["probes_failed"])
        self.assertEqual(["provider_b"],emitted[-1]["failed_providers"])
        self.assertTrue(all(not r["android_original_receipt_verified"]
                            for r in emitted[:-1]))

    def test_timeout_from_real_runner_fails_without_deadlock(self):
        cases={"timeout_provider":("test.py",[])}
        out=io.StringIO()
        with patch.object(engine.subprocess,"run",
                          side_effect=subprocess.TimeoutExpired("test",82)):
            with contextlib.redirect_stdout(out):
                code=engine.main(cases)
        self.assertEqual(1,code)
        summary=json.loads(out.getvalue().splitlines()[-1])
        self.assertEqual(["timeout_provider"],summary["failed_providers"])


if __name__=="__main__":
    unittest.main()
