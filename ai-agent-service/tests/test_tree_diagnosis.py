import pytest

from app.api.tree_practice_api import public_handout
from app.rag.tree_diagnosis import diagnose, main


def problem(nodes):
    return {"topic": "binary_tree", "order": "preorder", "nodes": nodes, "answer": ["A"]}


@pytest.mark.parametrize(
    "nodes",
    [
        [{"id": "A", "left": "B"}],
        [{"id": "A", "left": "B", "right": "B"}, {"id": "B"}],
        [{"id": "A"}, {"id": "B", "left": "C"}, {"id": "C", "right": "B"}],
        [{"id": "A", "left": "A"}],
    ],
)
def test_invalid_graph_prompts_for_correction(nodes):
    assert diagnose(problem(nodes), [public_handout()])["decision"] == "clarify"


def test_wrong_citation_prevents_answer_even_when_tree_is_valid():
    wrong_evidence = dict(public_handout(), document_name="cache-address-diagnosis.md")
    result = diagnose(problem([{"id": "A"}]), [wrong_evidence])
    assert result["decision"] == "refuse"
    assert "expected" not in result


def test_agentarts_code_node_accepts_serialized_inputs():
    import json

    request = json.dumps(problem([{"id": "A"}]))
    result = json.loads(main({"problem_json": request, "output_list": [public_handout()]})["result_json"])
    assert result["decision"] == "confirm"
    assert result["expected"] == ["A"]
    assert result["citation"]["marker"] == "EDUPATH-TREE-DFS-V1"
