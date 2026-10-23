"""G01 fixture validation and canonical hash reference; not a production engine.

Run with Python 3.10+ and jsonschema 4.x: python docs/contracts/validate.py
"""
from copy import deepcopy
from decimal import Decimal
from hashlib import sha256
import json
from pathlib import Path
import re

from jsonschema import Draft202012Validator, FormatChecker
from jsonschema.exceptions import ValidationError

ROOT = Path(__file__).resolve().parent
SORT_KEYS = {
    'positions': 'instrumentId', 'shocks': 'instrumentId',
    'beforeWeights': 'instrumentId', 'afterWeights': 'instrumentId',
    'instruments': 'instrumentId', 'evidence': 'evidenceId',
    'marketInputs': 'evidenceRef', 'metrics': 'metricId',
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def unique_pairs(pairs):
    out = {}
    for key, value in pairs:
        require(key not in out, f'duplicate JSON key: {key}')
        out[key] = value
    return out


def read_json(path):
    return json.loads(path.read_text(encoding='utf-8'), object_pairs_hook=unique_pairs)


def canonical_value(value, field=None):
    require(not isinstance(value, float), 'JSON floating point numbers are forbidden')
    if isinstance(value, dict):
        return {key: canonical_value(item, key) for key, item in value.items()}
    if isinstance(value, list):
        result = [canonical_value(item) for item in value]
        if field in SORT_KEYS:
            result.sort(key=lambda item: item[SORT_KEYS[field]])
        elif field == 'gaps':
            result.sort(key=lambda item: (item['code'], item['ref'] or ''))
        return result
    return value


def content_hash(value):
    encoded = json.dumps(canonical_value(value), ensure_ascii=False,
                         sort_keys=True, separators=(',', ':'), allow_nan=False)
    return 'sha256:' + sha256(encoded.encode('utf-8')).hexdigest()


def portfolio_payload(portfolio):
    keys = ('schemaVersion', 'hashAlgorithm', 'baseCurrency', 'valuationBasis',
            'totalValue', 'asOf', 'dataMode', 'positions')
    return {key: portfolio[key] for key in keys}


def research_payload(snapshot):
    return {key: value for key, value in snapshot.items()
            if key not in ('snapshotId', 'frozenAt', 'snapshotHash')}


def keyed(items, key):
    result = {item[key]: item for item in items}
    require(len(result) == len(items), f'duplicate {key}')
    return result


def validate_example(data, validator):
    """Check the frozen fixture's links and the G01 minimum report contract."""
    validator.validate(data)
    portfolio, decision, plan = data['portfolio'], data['decision'], data['plan']
    snapshot, scenario, report = data['researchSnapshot'], data['scenario'], data['report']
    manifest, bundle, result = snapshot['manifest'], report['metricBundle'], report['scenarioResult']
    instruments = keyed(data['instruments'], 'instrumentId')
    positions = keyed(portfolio['positions'], 'instrumentId')
    shocks = keyed(scenario['shocks'], 'instrumentId')
    evidence = keyed(data['evidence'], 'evidenceId')
    metrics = keyed(bundle['metrics'], 'metricId')
    by_name = keyed(bundle['metrics'], 'name')
    hypotheses = keyed(decision['hypotheses'], 'hypothesisId')
    claims = keyed(report['claims'], 'claimId')
    run_id, snapshot_hash = snapshot['runId'], snapshot['snapshotHash']

    require(decision['inputStatus'] == 'CONFIRMED', 'input must be confirmed before Run')
    require(decision['portfolioSnapshotId'] == portfolio['snapshotId'], 'decision portfolio mismatch')
    require(Decimal(portfolio['totalValue']) > 0, 'total value must be positive')
    require(sum(Decimal(p['weight']) for p in positions.values()) == 1, 'weights must sum exactly to one')
    require(set(positions) == set(instruments) == set(shocks), 'instruments/positions/shocks mismatch')
    require(sum(i['instrumentType'] != 'CASH' for i in instruments.values()) <= 3, 'too many noncash assets')
    for position in positions.values():
        require(position['quoteCurrency'] == portfolio['baseCurrency'] == decision['baseCurrency']
                == instruments[position['instrumentId']]['quoteCurrency'], 'currency mismatch')
    require(all(-1 <= Decimal(s['shock']) <= 1 for s in shocks.values()), 'shock outside P0 input range')
    action = decision['action']
    target, funding = action['instrumentId'], action['fundingInstrumentId']
    require(target in positions and funding in positions and target != funding, 'invalid funding asset')
    require(instruments[target]['instrumentType'] in ('STOCK', 'ETF')
            and instruments[funding]['instrumentType'] == 'CASH', 'P0 funding must be cash')
    original = {key: Decimal(p['weight']) for key, p in positions.items()}
    delta = (Decimal(action['targetWeight']) - original[target] if action['allocationMode'] == 'TARGET_WEIGHT'
             else Decimal(action['deltaPercentagePoints']) / 100)
    require(delta > 0, 'P0 reallocation must increase target weight')
    changed = dict(original)
    changed[target] += delta
    changed[funding] -= delta
    require(min(changed.values()) >= 0 and sum(changed.values()) == 1, 'insufficient funding')
    equivalent = data['equivalentTargetAction']
    require(equivalent['instrumentId'] == target and equivalent['fundingInstrumentId'] == funding
            and Decimal(equivalent['targetWeight']) == changed[target], 'target and delta modes differ')
    require(keyed(result['beforeWeights'], 'instrumentId') == positions, 'before weights mismatch')
    after = keyed(result['afterWeights'], 'instrumentId')
    require(set(after) == set(changed), 'after instruments mismatch')
    require(all(Decimal(after[k]['weight']) == value and after[k]['quoteCurrency'] == portfolio['baseCurrency']
                for k, value in changed.items()), 'after weights mismatch')
    before_return = sum(original[k] * Decimal(shocks[k]['shock']) for k in original)
    after_return = sum(changed[k] * Decimal(shocks[k]['shock']) for k in changed)
    total = Decimal(portfolio['totalValue'])
    expected_values = {
        'WEIGHT_CHANGE': delta * 100,
        'TARGET_WEIGHT_AFTER': changed[target],
        'SCENARIO_RETURN_BEFORE': before_return, 'SCENARIO_RETURN_AFTER': after_return,
        'SCENARIO_PNL_BEFORE': total * before_return, 'SCENARIO_PNL_AFTER': total * after_return,
        'SCENARIO_RETURN_CHANGE': (after_return - before_return) * 100,
    }
    require(set(expected_values) <= set(by_name), 'minimum deterministic metrics missing')
    for name, value in expected_values.items():
        metric = by_name[name]
        require(metric['status'] == 'CALCULABLE' and Decimal(metric['value']) == value, f'bad metric {name}')
        require(metric['sampleWindow'] is None and metric['sampleCount'] == 0, 'scenario is not a historical sample')
        require(metric['parameters']['valuationBasis'] == portfolio['valuationBasis'], 'valuation basis mismatch')
        require(metric['formulaVersion'] == {'WEIGHT_CHANGE': 'weight-change-v1', 'TARGET_WEIGHT_AFTER': 'weight-after-v1'}.get(name, 'direct-shock-v1'), 'formula mismatch')
        if name.startswith('SCENARIO_PNL'):
            require(metric['unit'] == ('BASE_CURRENCY_UNIT' if portfolio['valuationBasis'] == 'NORMALIZED' else 'CURRENCY')
                    and metric['currency'] == portfolio['baseCurrency'], 'PnL unit/currency mismatch')
    expected = data['expected']
    for key, value in [('beforeReturn', before_return), ('afterReturn', after_return),
                       ('beforePnl', before_return * total), ('afterPnl', after_return * total),
                       ('returnChangePercentagePoints', (after_return - before_return) * 100),
                       ('increasedLoss', (before_return - after_return) * total)]:
        require(Decimal(expected[key]) == value, f'expected {key} mismatch')
    # The sample arrays use the documented gold / silver / cash display order.
    require([Decimal(w) for w in expected['beforeWeights']] == [Decimal('0.2'), Decimal('0.1'), Decimal('0.7')]
            and [Decimal(w) for w in expected['afterWeights']] == [Decimal('0.3'), Decimal('0.1'), Decimal('0.6')], 'fixture display weights mismatch')

    decision_ref = {'decisionId': decision['decisionId'], 'version': decision['version']}
    portfolio_ref = {'snapshotId': portfolio['snapshotId'], 'version': portfolio['version'], 'contentHash': portfolio['contentHash']}
    require(snapshot['decisionRef'] == report['decisionRef'] == plan['decisionRef'] == decision_ref, 'decision version mismatch')
    require(snapshot['portfolioRef'] == report['portfolioRef'] == portfolio_ref, 'portfolio version mismatch')
    request = data['createRunRequest']
    require(request['decisionVersion'] == decision['version'] and request['portfolioSnapshotId'] == portfolio['snapshotId']
            and request['portfolioVersion'] == portfolio['version'], 'Run request version mismatch')
    for item in (scenario, report, bundle, result):
        require(item['runId'] == run_id and item['researchSnapshotId'] == snapshot['snapshotId']
                and item['snapshotHash'] == snapshot_hash, 'cross Run/snapshot binding')
    require(plan['runId'] == run_id and scenario['portfolioSnapshotId'] == portfolio['snapshotId']
            and result['scenarioId'] == scenario['scenarioId'], 'scenario/plan binding')
    require(portfolio['dataMode'] == snapshot['dataMode'] == report['dataMode'] == request['dataMode'] == 'SYNTHETIC', 'fixture mode must remain SYNTHETIC')
    require(plan['hypothesisIds'] == list(hypotheses), 'plan hypotheses mismatch')
    steps = keyed(plan['steps'], 'stepKey')
    visited, active = set(), set()

    def visit(key):
        require(key in steps and key not in active, 'unknown or cyclic plan dependency')
        if key in visited:
            return
        active.add(key)
        for dependency in steps[key]['dependsOn']:
            visit(dependency)
        active.remove(key)
        visited.add(key)

    for key in steps:
        visit(key)

    manifest_evidence = keyed(manifest['evidence'], 'evidenceId')
    require(set(manifest_evidence) == set(evidence), 'evidence manifest mismatch')
    for key, item in evidence.items():
        require(item['runId'] == run_id and item['dataMode'] == 'SYNTHETIC', 'cross Run evidence/mode')
        require(item['quality'] == 'VALID' and item['storagePolicy'] != 'NO_STORE', 'unusable published evidence')
        require(manifest_evidence[key] == {k: item[k] for k in ('evidenceId', 'payloadHash', 'storagePolicy')}, 'evidence hash mismatch')
        require(item['payloadRef'] == 'input_demo' and item['payloadHash'] == content_hash(data['calculationInput']), 'fixture payload mismatch')
    require(any(e['type'] == 'USER_INPUT' for e in evidence.values()), 'minimum user input evidence missing')
    require(set(report['evidenceRefs']) == set(evidence), 'report evidence mismatch')
    require(set(result['metricRefs']) == set(metrics), 'scenario metric references mismatch')
    for metric in metrics.values():
        require(set(metric['evidenceRefs']) <= set(evidence), 'unknown metric evidence')
    assessments = keyed(report['hypothesisAssessments'], 'hypothesisId')
    require(set(assessments) == set(hypotheses), 'missing hypothesis assessment')
    for assessment in assessments.values():
        require(set(assessment['evidenceRefs']) <= set(evidence) and set(assessment['metricRefs']) <= set(metrics), 'unknown assessment reference')
        require(assessment['supportStatus'] == expected['hypothesisStatus'], 'macro hypothesis must stay unverified')
    for claim in claims.values():
        require(claim['runId'] == run_id and claim['snapshotHash'] == snapshot_hash, 'cross Run claim')
        require(claim['hypothesisId'] in hypotheses, 'unknown claim hypothesis')
        require(set(claim['evidenceRefs']) <= set(evidence) and set(claim['metricRefs']) <= set(metrics), 'unknown claim reference')
        require(claim['evidenceRefs'] or claim['metricRefs'] or claim['unknowns'] or claim['conditions'], 'unqualified unsupported claim')
        if report['generationMode'] == 'DETERMINISTIC_FALLBACK':
            require(claim['role'] == 'DETERMINISTIC', 'fallback cannot impersonate a model role')
        for text in [claim['text'], *claim['conditions'], *claim['invalidationConditions'], *claim['unknowns']]:
            require(re.search(r'\d', text) is None, 'unapproved number in claim text')
    verdict = report['verdict']
    require(set(verdict['supportingClaimIds'] + verdict['opposingClaimIds']) <= set(claims), 'unknown verdict claim')
    require(verdict['verdict'] == expected['verdict'] and report['status'] == expected['runStatus'], 'fallback outcome mismatch')
    require(report['roleResults']['judge'] == 'MISSING' and report['generationMode'] == 'DETERMINISTIC_FALLBACK', 'Judge fallback mismatch')
    require({'REALLOCATION', 'DIRECT_PRICE_SHOCK'} <= set(report['scope']['requiredAnalyses']), 'minimum scope missing')
    require(report['gaps'] and report['limitations'], 'fallback limitations missing')
    require(report['validatedAt'] <= report['publishedAt'], 'report published before validation')

    rules = keyed(result['ruleResults'], 'ruleId')
    constraints = decision['constraints']
    for rule_id, observed, threshold in (
        ('MAX_TARGET_WEIGHT_AFTER', changed[target], Decimal(constraints['maxTargetWeight'])),
        ('MAX_SCENARIO_LOSS_BEFORE', max(Decimal(0), -before_return), Decimal(constraints['maxScenarioLoss'])),
        ('MAX_SCENARIO_LOSS_AFTER', max(Decimal(0), -after_return), Decimal(constraints['maxScenarioLoss'])),
    ):
        require(rule_id in rules, 'minimum rule missing')
        rule = rules[rule_id]
        require(Decimal(rule['threshold']) == threshold and rule['status'] == ('PASS' if observed <= threshold else 'FAIL'), 'rule evaluation mismatch')
        require(set(rule['metricRefs']) <= set(metrics), 'unknown rule metric')
        require(all(metrics[key]['unit'] == rule['unit'] for key in rule['metricRefs']), 'rule/metric unit mismatch')

    require(portfolio['contentHash'] == content_hash(portfolio_payload(portfolio)), 'portfolio hash mismatch')
    require(data['calculationInput']['portfolioContentHash'] == portfolio['contentHash']
            and data['calculationInput']['action'] == action
            and data['calculationInput']['shocks'] == scenario['shocks'], 'calculation input mismatch')
    input_hash = content_hash(data['calculationInput'])
    require(bundle['inputHash'] == result['inputHash'] == input_hash, 'calculation input hash mismatch')
    manifest_metrics = keyed(manifest['metrics'], 'metricId')
    require(set(manifest_metrics) == set(metrics), 'metric manifest mismatch')
    for key, metric in metrics.items():
        require(metric['inputHash'] == input_hash and manifest_metrics[key] == {
            'metricId': key, 'inputHash': input_hash, 'formulaVersion': metric['formulaVersion'], 'contentHash': content_hash(metric)
        }, 'metric content hash mismatch')
    manifest_instruments = keyed(manifest['instruments'], 'instrumentId')
    require(set(manifest_instruments) == set(instruments), 'instrument manifest mismatch')
    for key, instrument in instruments.items():
        require(manifest_instruments[key]['metadataHash'] == content_hash(instrument), 'instrument metadata hash mismatch')
    require(manifest['planHash'] == content_hash(plan), 'plan hash mismatch')
    require(snapshot_hash == content_hash(research_payload(snapshot)), 'research hash mismatch')
    versions = report['versions']
    require(versions['contractVersion'] == report['schemaVersion'], 'contract version mismatch')
    for key in ('engineVersion', 'qualityPolicyVersion', 'promptVersions', 'modelConfigVersion'):
        require(versions[key] == manifest[key], f'version mismatch: {key}')
    require(scenario['engineVersion'] == bundle['engineVersion'] == manifest['engineVersion']
            == data['calculationInput']['engineVersion'], 'engine version mismatch')


def main():
    schema = read_json(ROOT / 'schemas/contract.schema.json')
    Draft202012Validator.check_schema(schema)
    validator = Draft202012Validator(schema, format_checker=FormatChecker())
    data = read_json(ROOT / 'examples/gold-silver-cash.json')
    validate_example(data, validator)
    print('PASS: JSON Schema and 20/10/70 -> 30/10/60 linked fixture')
    checks = [
        ('mixed allocation units', lambda d: d['decision']['action'].update(targetWeight='0.3')),
        ('numeric weight', lambda d: d['portfolio']['positions'][0].update(weight=0.2)),
        ('overprecision weight', lambda d: d['portfolio']['positions'][0].update(weight='0.20000000001')),
        ('weights do not sum to one', lambda d: d['portfolio']['positions'][0].update(weight='0.21')),
        ('mixed currency', lambda d: d['portfolio']['positions'][0].update(quoteCurrency='TWD')),
        ('unconfirmed input', lambda d: d['decision'].update(inputStatus='READY', confirmedAt=None)),
        ('insufficient cash funding', lambda d: d['decision']['action'].update(deltaPercentagePoints='80')),
        ('unknown reference E99', lambda d: d['report']['claims'][0]['evidenceRefs'].append('E99')),
        ('cross Run evidence', lambda d: d['evidence'][0].update(runId='run_other')),
        ('snapshot mismatch', lambda d: d['report'].update(snapshotHash='sha256:' + '0' * 64)),
        ('missing minimum metric', lambda d: d['report']['metricBundle']['metrics'].pop()),
        ('wrong metric unit', lambda d: d['report']['metricBundle']['metrics'][0].update(unit='RATIO')),
        ('unavailable metric with zero', lambda d: d['report']['metricBundle']['metrics'][0].update(status='NOT_CALCULABLE', value='0', reason='missing input')),
        ('fabricated text number', lambda d: d['report']['claims'][0].update(text='预期收益 87%')),
        ('fallback marked completed', lambda d: d['report'].update(status='COMPLETED')),
        ('hidden fallback gaps', lambda d: d['report'].update(gaps=[])),
        ('incorrect arithmetic', lambda d: d['report']['metricBundle']['metrics'][1].update(value='-0.06')),
        ('stale decision version', lambda d: d['createRunRequest'].update(decisionVersion=2)),
        ('missing shock', lambda d: d['scenario']['shocks'].pop()),
        ('shock below total loss', lambda d: d['scenario']['shocks'][0].update(shock='-1.1')),
        ('rule and metric unit mismatch', lambda d: d['report']['scenarioResult']['ruleResults'][0].update(metricRefs=['M_WEIGHT_CHANGE'])),
        ('plan cycle', lambda d: d['plan']['steps'][0]['dependsOn'].append(d['plan']['steps'][0]['stepKey'])),
        ('unretained published payload', lambda d: d['evidence'][0].update(storagePolicy='METADATA_ONLY')),
        ('unknown field', lambda d: d['decision'].update(ownerId='untrusted')),
    ]
    for label, mutate in checks:
        candidate = deepcopy(data)
        mutate(candidate)
        try:
            validate_example(candidate, validator)
        except (ValueError, ValidationError):
            pass
        else:
            raise ValueError(f'negative case unexpectedly accepted: {label}')
        print(f'PASS: rejects {label}')
    original_hash = content_hash(portfolio_payload(data['portfolio']))
    reordered = deepcopy(data['portfolio'])
    reordered = dict(reversed(list(reordered.items())))
    reordered['positions'].reverse()
    reordered['snapshotId'] = 'different_id'
    require(content_hash(portfolio_payload(reordered)) == original_hash, 'hash must ignore order/identity')
    reordered['asOf'] = '2026-10-22T00:00:00.000Z'
    require(content_hash(portfolio_payload(reordered)) != original_hash, 'hash must include source time')
    print('PASS: canonical hash stable under object/position order and identity; changes with source time')
    print(f'G01 contract checks passed: schema, linked fixture, {len(checks)} rejection cases, hash invariants')


if __name__ == '__main__':
    main()
