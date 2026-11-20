"""Write the AgentArts Code node using the project's tested Cache rules."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RULES = ROOT / "agentarts" / "cache_diagnosis.py"
TARGET = ROOT / "submission" / "ict-final-integration" / "agentarts-cache-code-node.py"

ADAPTER = '''
def main(args):
    _, state = prepare(args.get("query"))
    result = complete(state, args.get("output_list"))
    citations = json.loads(result["citations_json"])
    output = {
        "decision": result["decision"],
        "mistake": result["mistake"],
        "explanation": result["answer"].partition("\\n\\n依据 [E1]")[0],
        "citation": citations[0] if citations else None,
    }
    return {"answer": result["answer"], "result_json": json.dumps(output, ensure_ascii=False)}
'''

TARGET.parent.mkdir(parents=True, exist_ok=True)
rules, marker, _ = RULES.read_text(encoding="utf-8").partition("\ndef main(args):")
if not marker:
    raise SystemExit("Unexpected Cache tool entry point")
TARGET.write_text(rules.rstrip() + "\n\n" + ADAPTER.lstrip(), encoding="utf-8", newline="\n")
