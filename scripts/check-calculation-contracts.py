"""Check synthetic G13/G14 test exports against G01 Metric schema and independent arithmetic.

Run the Java integration tests first, then execute with Python + jsonschema.
Only reads the test exports; does not call services, providers or models.
"""
import hashlib
import json
from decimal import Decimal
from pathlib import Path
from jsonschema import Draft202012Validator, FormatChecker

ROOT = Path(__file__).resolve().parents[1]


def read(path):
    return json.loads((ROOT / path).read_text(encoding="utf-8"))


def digest(value):
    return "sha256:" + hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False,
                                                  separators=(",", ":"), allow_nan=False).encode("utf-8")).hexdigest()


schema = read("docs/contracts/schemas/contract.schema.json")
validator = Draft202012Validator({"$defs": schema["$defs"], "$ref": "#/$defs/Metric"}, format_checker=FormatChecker())


def check_metrics(result):
    ids = set()
    for metric in result["metrics"]:
        validator.validate(metric)
        assert metric["metricId"] not in ids
        ids.add(metric["metricId"])
        assert metric["inputHash"] == result["inputHash"]
        assert result["metricHashes"][metric["metricId"]] == digest(metric)
    assert set(result["metricHashes"]) == ids
    return {m["name"]: m for m in result["metrics"]}


bundle = read("backend/target/g13-bundle.json")
check_metrics(bundle)
assert bundle["runId"] is None and bundle["snapshotHash"] is None
assert {ref for asset in bundle["assets"] for ref in asset["metricRefs"]} == set(bundle["metricHashes"])
vol = next(m for m in bundle["metrics"] if m["name"] == "ANNUALIZED_VOLATILITY")
assert vol["sampleCount"] == 2 and vol["parameters"]["minimumSamples"] == 30
assert vol["status"] == "NOT_CALCULABLE" and vol["value"] is None
print("PASS: G13 Metric schema, hashes, references and insufficient-sample contract")

scenario = read("backend/target/g14-scenario.json")
metrics = check_metrics(scenario)
assert set(scenario["metricRefs"]) == set(scenario["metricHashes"])
portfolio = read("backend/src/test/resources/portfolio-create.json")
portfolio_content = {k: portfolio[k] for k in ("schemaVersion", "baseCurrency", "valuationBasis", "totalValue", "asOf", "dataMode", "positions")}
portfolio_content["positions"] = sorted(portfolio_content["positions"], key=lambda p: p["instrumentId"])
portfolio_content["hashAlgorithm"] = "investlens-canonical-json-v1"
assert digest(portfolio_content) == scenario["portfolioRef"]["contentHash"]
content = dict(schemaVersion="1.0.0", engineVersion=scenario["engineVersion"],
               portfolioContentHash=scenario["portfolioRef"]["contentHash"], action=scenario["action"],
               shocks=sorted(scenario["shocks"], key=lambda s: s["instrumentId"]), marketInputs=[],
               parameters=dict(dataMode=scenario["dataMode"], valuationBasis=portfolio["valuationBasis"],
                               costsIncluded=False, constraints=scenario["constraints"]))
assert digest(content) == scenario["inputHash"]
shocks = {s["instrumentId"]: Decimal(s["shock"]) for s in scenario["shocks"]}
returns = []
for suffix in ("Before", "After"):
    weights = {w["instrumentId"]: Decimal(w["weight"]) for w in scenario[suffix.lower() + "Weights"]}
    assert sum(weights.values()) == Decimal(1) and set(weights) == set(shocks)
    value = sum(weights[key] * shocks[key] for key in weights)
    returns.append(value)
    assert Decimal(metrics["SCENARIO_RETURN_" + suffix.upper()]["value"]) == value
    assert Decimal(metrics["SCENARIO_PNL_" + suffix.upper()]["value"]) == Decimal(portfolio["totalValue"]) * value
assert returns == [Decimal("-0.065"), Decimal("-0.085")]
assert Decimal(metrics["SCENARIO_RETURN_CHANGE"]["value"]) == (returns[1] - returns[0]) * 100
assert scenario["concentration"]["unit"] == "RATIO"
print("PASS: G14 Metric schema, hashes, complete input hash, weight conservation and -6.5%/-8.5% arithmetic")
