"""평가 항목 측정 명령 (measure_evaluation)."""

import csv
import tempfile
from io import StringIO
from pathlib import Path

from django.core.management import call_command
from django.core.management.base import CommandError
from django.test import TestCase


class MeasureEvaluationTests(TestCase):
    def test_requires_seed(self):
        with self.assertRaises(CommandError):
            call_command("measure_evaluation", stdout=StringIO())

    def test_all_voice_commands_pass_on_fresh_seed(self):
        call_command("seed_demo", stdout=StringIO())
        out = StringIO()
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "result.csv"
            call_command("measure_evaluation", csv=str(path), stdout=out)
            with open(path, encoding="utf-8-sig", newline="") as f:
                rows = list(csv.reader(f))
        text = out.getvalue()
        self.assertIn("결과: 20/20 성공", text)
        self.assertIn("목표 달성", text)
        self.assertEqual(len(rows), 1 + 20 + 2)  # 머리글 + 음성 20 + 응답 시간 2
        self.assertTrue(all(r[4] in ("성공", "달성") for r in rows[1:]))

    def test_list_only(self):
        out = StringIO()
        call_command("measure_evaluation", list=True, stdout=out)
        self.assertIn('20. [화면: 효도 힐링 투어] "3번"', out.getvalue())
