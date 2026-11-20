"""Regression checks for the AgentArts Cache diagnostic code nodes."""

import json
import unittest
from pathlib import Path

from agentarts.cache_diagnosis import DOCUMENT, SEARCH_QUERY, main, prepare


AGENTARTS = Path(__file__).resolve().parent.parent / "agentarts"


class CacheDiagnosisTests(unittest.TestCase):
    def setUp(self):
        handout = (AGENTARTS / (DOCUMENT + ".md")).read_text(encoding="utf-8")
        self.hits = [{"document_name": DOCUMENT + ".md", "subtitle": "地址字段计算",
                      "file_id": "test-file", "content": handout}]
        self.stem = "按字节寻址，32 位地址，Cache 数据容量 4 KiB，块大小 16 B，"

    def assess(self, query, hits):
        first = main({"query": query})
        return main({"state": first["state"], "output_list": hits})

    def test_baseline_decisions_and_evidence(self):
        rows = (AGENTARTS / "phase0-baseline-cases.jsonl").read_text(encoding="utf-8").splitlines()
        for line in rows:
            case = json.loads(line)
            with self.subTest(case=case["id"]):
                expected = case["expected"]
                route = expected["decision"].replace("refuse_if_insufficient", "refuse")
                search, _ = prepare(case["input"])
                hits = self.hits if search == SEARCH_QUERY else []
                result = self.assess(case["input"], hits)
                self.assertEqual(result["decision"], route)
                if "tag_bits" in expected:
                    self.assertIn("22 / 6 / 4", result["answer"])
                    self.assertEqual(json.loads(result["citations_json"])[0]["file_id"], "test-file")
                    self.assertIn("摘录「总块数为 C ÷ B", result["answer"])
                if "mistake" in expected:
                    self.assertEqual(result["mistake"], expected["mistake"])
                    expected_practice = "二路相联" if "index" in expected["mistake"] else "32 B 块"
                    self.assertIn(expected_practice, result["answer"])
                if "missing" in expected:
                    self.assertIn("相联度", result["answer"])
                    self.assertEqual(result["citations_json"], "[]")

    def test_all_mapping_types(self):
        for mapping, bits in (("直接映射", (20, 8, 4)), ("四路组相联", (22, 6, 4)),
                              ("全相联", (28, 0, 4))):
            with self.subTest(mapping=mapping):
                _, state = prepare(self.stem + mapping)
                self.assertEqual(tuple(state["geometry"]["bits"].values()), bits)

    def test_invalid_geometry(self):
        for stem in (self.stem + "0路组相联", self.stem + "3路组相联",
                     self.stem.replace("16 B", "0 B") + "直接映射",
                     self.stem.replace("16 B", "15 B") + "直接映射",
                     self.stem.replace("32 位", "2 位") + "四路组相联",
                     self.stem.replace("按字节寻址", "按字寻址") + "四路组相联"):
            with self.subTest(stem=stem):
                self.assertEqual(self.assess(stem, self.hits)["decision"], "clarify")

    def test_missing_or_wrong_course_evidence(self):
        for hits in ([], [{**self.hits[0], "document_name": "other.md"}],
                     [{**self.hits[0], "content": "EDUPATH-CACHE-ADDR-V1 只有组数、Offset、Index 等别名"}]):
            with self.subTest(hits=hits):
                result = self.assess(self.stem + "四路组相联", hits)
                self.assertEqual(result["decision"], "refuse")
                self.assertNotIn("22 / 6 / 4", result["answer"])

    def test_corrupt_node_state(self):
        for bad in ("not-json", "null", '{"decision":"confirm"}'):
            with self.subTest(state=bad):
                result = main({"state": bad, "output_list": self.hits})
                self.assertEqual(result["decision"], "tool_error")
                self.assertEqual(result["citations_json"], "[]")

    def test_one_node_with_compact_chinese_query(self):
        result = main({"query": "按字节寻址的Cache地址为32位，数据容量4KiB且块大小16B，四路组相联。",
                       "output_list": self.hits})
        self.assertEqual(result["decision"], "explain")
        self.assertIn("22 / 6 / 4", result["answer"])
