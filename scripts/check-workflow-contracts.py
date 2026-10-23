"""Validate the real synthetic Run API test export against the frozen G01 schema."""
import json
from pathlib import Path
from jsonschema import Draft202012Validator, FormatChecker

root = Path(__file__).resolve().parents[1]
schema = json.loads((root / "docs/contracts/schemas/contract.schema.json").read_text(encoding="utf-8"))
run = json.loads((root / "backend/target/g17-run.json").read_text(encoding="utf-8"))
Draft202012Validator({"$defs": schema["$defs"], "$ref": "#/$defs/Run"}, format_checker=FormatChecker()).validate(run)
assert run["status"] == "QUEUED" and run["stage"] is None
assert run["dataMode"] == "SYNTHETIC" and run["reportId"] is None
assert run["budget"]["dataAttempts"] == run["budget"]["modelAttempts"] == 0
assert run["budget"]["maxDataAttempts"] == 8 and run["budget"]["maxModelAttempts"] == 5
print("PASS: real Run API export matches G01 schema, fixed limits, queued state and null report")
