#!/usr/bin/env python3
"""Generate a patch for the observed ledger, never for corpus expectations.

Run after both native and Brikk corpus lanes. Classification is deliberately
closed: a new native-PSI failure needs triage, not an automatically accepted flag.
"""
import collections
import argparse
import hashlib
import json
import pathlib

ROOT = pathlib.Path(__file__).resolve().parents[1]
CORPUS = ROOT / "src/test/resources/relation-corpus"
reports = {lane: json.loads((ROOT / f"build/relation-corpus/duckdb-{lane}-261.json").read_text())
           for lane in ("native", "brikk")}
cases = {case["id"]: case for case in map(json.loads, (CORPUS / "cases.jsonl").read_text().splitlines())}
old = json.loads((CORPUS / "duckdb-disagreements.json").read_text())
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--write", action="store_true", help="Regenerate only the ledger and reproducer resources, never facts/cases/witnesses")
arguments = parser.parse_args()


def classify(case_id, axis, result, mismatch):
    if axis == "resolve":
        probes = {probe["start"]: probe for probe in result["resolveEvidence"]}
        failed = [probes[expected["start"]] for expected, actual in zip(mismatch["expected"], mismatch["actual"])
                  if expected != actual]
        if failed and all(not probe["nativeReference"] for probe in failed):
            return "b", "flat-pipe-native-reference", "PipeStatementGate / ExternalRelationResolveExtension", (
                "Core resolves the authored name, but the shared statement gate leaves flat PSI without a SqlReferenceExpression. "
                "The native resolve extension is never called. A shared flat-PIPE reference bridge is needed; no plugin analyzer substitution is made.")
    if case_id in {"duckdb-where-projection-alias", "duckdb-lateral-select-alias"} and axis == "resolve":
        return "c", "stock-computed-alias-rules", "Pg native SQL resolver", (
            "The native reference exists but has no variants for DuckDB's computed projection-alias rules. "
            "Core and shared completion are correct; stock PostgreSQL-family alias behavior is retained, not replaced with a plugin resolver.")
    if case_id == "unknown-table-open-catalog" and axis == "diagnostics":
        return "c", "stock-open-catalog-inspection", "SqlResolveInspection", (
            "The shared open-catalog analysis reports nothing. The separately enabled stock inspection still reports an unresolved table; "
            "there is no provider fact or closed-catalog proof that would let the shared suppressor own it.")
    if case_id == "self-join-same-slot" and axis == "resolve":
        return "b", "self-join-occurrence-identity", "RelationColumnSymbol / ExternalRelationResolveExtension", (
            "Core finds two alias occurrences and reports AMBIGUOUS. The native variants are equal provider-column symbols, "
            "so native resolve picks that declaration and loses occurrence ambiguity. Declaration identity must not erase reference-occurrence ambiguity.")
    if case_id == "quoted-case-duckdb" and axis == "resolve":
        return "b", "quoted-column-case-bridge", "ExternalRelationResolveExtension / RelationColumnSymbol", (
            "Native and core start offsets agree, but no native variant survives for DuckDB's case-insensitive quoted column. "
            "The provider symbol's native name/quote matching needs to preserve the core verdict.")
    if case_id in {"ordinary-star-join-cte-duckdb", "ordinary-star-join-subquery-duckdb"} and axis == "resolve":
        return "b", "star-output-native-management", "RelationAnalysis.manages / ExternalRelationResolveExtension", (
            "Core exposes the DuckDB de-duplicated star column id_1. The native resolver has no candidate. "
            "Management of star-derived provider columns must not be lost because the inner join predicate also references their source facts.")
    if case_id == "ordinary-group-alias" and axis == "resolve":
        return "b", "projection-lineage-native-management", "RelationAnalysis.manages / ExternalRelationResolveExtension", (
            "Core preserves the provider column's lineage through the GROUP BY projection alias, but native resolve returns no candidate. "
            "The shared resolver excludes the unqualified projection output from its managed provider-column cases.")
    if case_id in {"set-nullability-names-intersect", "set-nullability-names-except"} and axis in {"resolve", "navigation"}:
        return "b", "renamed-set-lineage-native-management", "RelationAnalysis.manages / ExternalRelationGotoDeclarationHandler", (
            "Core retains the left input's provider provenance through the renamed set output. The native result is a SQL alias declaration, "
            "not that provider column; the shared management/deference policy loses the retained lineage.")
    if case_id in {"left-using-merged-star", "natural-merged-star"} and axis == "resolve":
        return "b", "merged-key-native-management", "RelationAnalysis.manages / ExternalRelationResolveExtension", (
            "Core selects the left provider's merged key, but native resolution disagrees or remains ambiguous. "
            "The shared layer does not make the core's merged/star-derived key authoritative over the stock table-type result.")
    if case_id.startswith("full-using-key-") and axis == "resolve":
        return "b", "computed-merged-key-native-management", "ExternalRelationResolveExtension / RelationAnalysis.manages", (
            "Core computes one merged FULL USING key with no single-provider provenance. Native resolution is not that computed column; "
            "the shared resolver manages provider columns only and supplies no native symbol for this output.")
    if case_id in {"ordinary-set-union-all", "set-column-count-mismatch"} and axis in {"resolve", "navigation"}:
        return "b", "set-output-native-deference", "ExternalRelationResolveExtension / ExternalRelationGotoDeclarationHandler", (
            "The native result retains a left-input declaration after core made the output computed or rejected the branch column count. "
            "The shared native/declaration deference policy leaves that stale input result visible.")
    if case_id == "local-alias-shadow" and axis == "completion":
        return "b", "shadowed-native-completion", "ExternalRelationCompletionContributor.match", (
            "The shared candidates correctly contain the inner physical columns, but an extra stock id item from the shadowed context is passed through. "
            "This reproduces with the optional native slot type adapter enabled; it is not enabled in the shipping plugin.")
    raise ValueError(f"Untriaged difference: {case_id}/{axis}")


shared, reasons, fingerprints = {}, {}, {}
issues = {}
counts = collections.Counter()
for lane, report in reports.items():
    assert report["logicalPassingCases"] == report["applicableCases"] == 167
    lane_flags, lane_fingerprints = {}, {}
    for result in report["results"]:
        case_id = result["id"]
        for axis in result["axes"]:
            if axis["matches"]:
                continue
            name = axis["axis"]
            lane_flags.setdefault(case_id, []).append(name)
            lane_fingerprints.setdefault(case_id, {})[name] = axis["fingerprint"]
            classification, issue, component, reason = classify(case_id, name, result, axis)
            if lane == "brikk":
                counts[classification] += 1
                reasons.setdefault(case_id, {})[name] = dict(classification=classification, issue=issue,
                    component=component, reason=reason, category=cases[case_id]["category"],
                    references=[probe["start"] for probe in result["resolveEvidence"]],
                    carets=[caret["offset"] for caret in cases[case_id]["carets"]])
                issues.setdefault(issue, (case_id, name, result, axis, classification, component, reason))
    lane_flags = {key: sorted(value) for key, value in sorted(lane_flags.items())}
    if shared:
        assert shared == lane_flags, "Different lane flags need separate triage"
    shared = lane_flags
    fingerprints[lane] = {"fingerprints": dict(sorted(lane_fingerprints.items()))}

ledger = dict(shared=shared, reasons=dict(sorted(reasons.items())), lanes=fingerprints,
    sourceHashes={name: hashlib.sha256((CORPUS / name).read_bytes()).hexdigest()
                  for name in ("facts.json", "cases.jsonl", "witnesses.json")},
    classificationCounts=dict(counts), prunedV1Flags=old.get("prunedV1Flags", []), notes=[
        "Phase 5 triage observes native SQL files and genuine Kotlin injections independently, without changing corpus expectations.",
        "Logical scope/references/order and execution pass 167/167; native-PSI differences are recorded separately with reasons and fingerprints.",
        "Normalization follows core's corpus contract: canonical SQL types, computed identity, and no assertion on unspecified computed SQL types.",
        "Completion is observed from actual items, matched to current shared candidates only by their actual presentation; raw items remain in the report.",
        "Native resolve never substitutes analyzer results for missing PSI. It normalizes a visible alias only after native identity/provenance independently agree.",
        "Physical native fixtures remain real SQL-file sources, with an explicit platform-resolves physical facts adapter; real LocalDataSource DasRelationFacts has a separate consumer test.",
        "The optional native slot table-type bridge was evaluated but is not enabled: it introduces additional lineage/navigation errors and a shadowed completion item.",
    ])
repros = []
for issue, (case_id, axis, result, mismatch, classification, component, reason) in sorted(issues.items()):
    case = cases[case_id]
    repros.append(dict(issue=issue, classification=classification, component=component, caseId=case_id,
        sql=case["sql"], relations=case["relations"], physicalTables=case["physicalTables"], calls=case["callContracts"],
        catalogKnowledge=case["catalogKnowledge"], referenceOffsets=[probe["start"] for probe in result["resolveEvidence"]],
        caretOffsets=[caret["offset"] for caret in case["carets"]], axis=axis, expected=mismatch["expected"], actual=mismatch["actual"],
        resolveEvidence=result["resolveEvidence"], presentationEvidence=result["presentationEvidence"], reason=reason))


def lines(value, indent=0):
    """Keep generated maps readable and each case on one line."""
    if isinstance(value, dict) and indent == 0:
        entries = list(value.items())
        yield "{"
        for index, (key, item) in enumerate(entries):
            text = json.dumps(item, ensure_ascii=False, separators=(",", ": "))
            yield "  " + json.dumps(key) + ": " + text + ("," if index < len(entries) - 1 else "")
        yield "}"
    else:
        yield json.dumps(value, ensure_ascii=False, separators=(",", ": "))


if not arguments.write:
    print("*** Begin Patch")
for name, value in [("duckdb-disagreements.json", ledger), ("duckdb-support-reproducers.json", {"factsFile": "facts.json", "reproducers": repros})]:
    if arguments.write:
        (CORPUS / name).write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")
        continue
    relative = "src/test/resources/relation-corpus/" + name
    if (CORPUS / name).exists():
        print("*** Delete File: " + relative)
    print("*** Add File: " + relative)
    for line in lines(value):
        print("+" + line)
if not arguments.write:
    print("*** End Patch")
