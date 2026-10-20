#!/usr/bin/env python3
"""Reanalyze metric-only prewarm data, including declared sensitivity checks."""

import argparse
from collections import Counter
import copy
import importlib.util
import json
from pathlib import Path
import random
import statistics

spec = importlib.util.spec_from_file_location("prewarm", Path(__file__).with_name("evaluate-prewarm.py"))
prewarm = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prewarm)


def read_rows(directory):
    return [json.loads(line) for line in (directory/"measurements.jsonl").read_text().splitlines()]


def pairs(rows, a="cold", b="prewarmed"):
    grouped = {}
    for row in rows:
        grouped.setdefault(row["block"], {})[row["arm"]] = row
    return [(v[a], v[b]) for v in grouped.values() if a in v and b in v]


def paired(rows, phase="paired", a="cold", b="prewarmed", metric="ttft_ms"):
    return prewarm.paired_summary(rows, phase, "ru_en", a, b, metric)


def tail(rows, metric):
    observed = [(a,b) for a,b in pairs(rows) if a.get(metric) is not None and b.get(metric) is not None]
    if not observed:
        return {"n": 0}
    strata = {}
    for i, (a, _) in enumerate(observed):
        strata.setdefault(a.get("wave",0),[]).append(i)
    rng = random.Random(20261022)
    estimates = []
    for _ in range(10000):
        sample = [observed[i] for group in strata.values() for i in rng.choices(group,k=len(group))]
        estimates.append(prewarm.percentile([a[metric] for a,b in sample],.9)-prewarm.percentile([b[metric] for a,b in sample],.9))
    return {"n": len(observed), "p90_saving_ms": prewarm.percentile([a[metric] for a,b in observed],.9)-prewarm.percentile([b[metric] for a,b in observed],.9),
            "ci95_ms": [prewarm.percentile(estimates,.025),prewarm.percentile(estimates,.975)],
            "cold_p95_ms": prewarm.percentile([a[metric] for a,b in observed],.95),
            "prewarmed_p95_ms": prewarm.percentile([b[metric] for a,b in observed],.95)}


def decision(result):
    if not result.get("n"):
        return "missing_data"
    absolute, relative = result["ci95_ms"], result["fraction_ci95"]
    if absolute[0] > 200 and relative[0] > .1:
        return "practical_threshold_supported"
    if absolute[1] < 200 or relative[1] < .1:
        return "specified_practical_threshold_not_supported_within_interval"
    if absolute[0] > 0 and relative[0] > 0:
        return "benefit_supported_magnitude_uncertain"
    return "inconclusive"


def analyze(study, transport=None):
    rows = read_rows(study)
    primary = [r for r in rows if r["phase"]=="paired" and r["scope"]=="ru_en"]
    result = {"primary": {metric: paired(primary,metric=metric) for metric in ("ttft_ms","completion_ms")},
              "primary_sensitivity": {}, "primary_tail": {}, "preparation": {}, "overlap": {}}
    for name, predicate in [("valid_json", lambda r:r.get("valid_json",False)),
                            ("content_check",lambda r:r.get("content_check",False)),
                            ("cold_first",lambda r:r.get("position")==0),
                            ("prewarm_first",lambda r:r.get("position")==1)]:
        selected=[]
        for a,b in pairs(primary):
            keep = predicate(a) if name.endswith("first") else predicate(a) and predicate(b)
            if keep:
                selected.extend((a,b))
        result["primary_sensitivity"][name] = {metric:paired(selected,metric=metric) for metric in ("ttft_ms","completion_ms")}
    for metric in ("ttft_ms","completion_ms"):
        result["primary_tail"][metric]=tail(primary,metric)
        result["primary"][metric]["decision"]=decision(result["primary"][metric])
    for scope in ("ru_en","ru"):
        preps=[r for r in rows if r["phase"]=="paired" and r["scope"]==scope and r["arm"]=="preparation"]
        durations=[r["completion_ms"] for r in preps if r.get("completion_ms") is not None]
        result["preparation"][scope]={"n":len(preps),"median_ms":statistics.median(durations),
            "p90_ms":prewarm.percentile(durations,.9),"p95_ms":prewarm.percentile(durations,.95),
            "zero_output":sum(r.get("output_tokens")==0 and not r.get("output_chars") and not r.get("text_deltas") for r in preps)}
    for lead in (0,.5,1,2,5):
        selected=[r for r in rows if r["phase"]=="overlap" and r["lead_s"]==lead]
        inference=[r for r in selected if r["arm"]=="overlap"]
        values=[r["actual_lead_ms"] for r in inference]
        entry={"n":len(inference),"actual_lead_median_ms":statistics.median(values),
               "actual_lead_range_ms":[min(values),max(values)],
               "known_readiness_count":sum("prewarm_success_at_dispatch" in r for r in inference),
               "prewarm_success_before_dispatch":sum(r.get("prewarm_success_at_dispatch") is True for r in inference),
               "inference_cache_hits":sum(r.get("cache_read_tokens",0)>0 for r in inference),
               "valid_json":sum(r.get("valid_json",False) for r in inference)}
        for metric in ("ttft_ms","completion_ms"):
            entry[metric]=paired(selected,"overlap","cold","overlap",metric)
        result["overlap"][str(lead)]=entry
    # Counterfactual blocking policy: measured completed preparation followed by
    # inference, minus an assumed lead. This is a simulation, not an overlap trial.
    result["blocking_policy_simulation"]={}
    for lead in (0,.5,1,2,5):
        durations={r["block"]:r["completion_ms"] for r in primary if r["arm"]=="preparation" and r.get("completion_ms") is not None and r["status"]=="completed"}
        adjusted=copy.deepcopy([r for r in primary if r["block"] in durations])
        for row in adjusted:
            if row["arm"]=="prewarmed" and durations.get(row["block"]) is not None:
                penalty=max(0,durations[row["block"]]-lead*1000)
                for metric in ("ttft_ms","completion_ms"):
                    if row.get(metric) is not None:
                        row[metric]+=penalty
        result["blocking_policy_simulation"][str(lead)]={metric:paired(adjusted,metric=metric) for metric in ("ttft_ms","completion_ms")}
    result["attempts"]={"recorded":len(rows),"status_counts":dict(Counter(r["status"] for r in rows)),
        "inferences":sum(r["arm"]!="preparation" for r in rows),
        "missing_ttft":sum(r["arm"]!="preparation" and r.get("ttft_ms") is None for r in rows),
        "invalid_json":sum(r["arm"]!="preparation" and not r.get("valid_json",False) for r in rows),
        "failed_content_check":sum(r["arm"]!="preparation" and not r.get("content_check",False) for r in rows)}
    result["primary_quality"]={arm:{"n":sum(r["arm"]==arm for r in primary),
        "valid_json":sum(r["arm"]==arm and r.get("valid_json",False) for r in primary),
        "content_check":sum(r["arm"]==arm and r.get("content_check",False) for r in primary)} for arm in ("cold","prewarmed","repeat")}
    if transport:
        transport_rows=read_rows(transport)
        result["transport"]={}
        for a,b in [("fresh","get_warmed"),("fresh","prewarm_warmed"),("get_warmed","prewarm_warmed")]:
            result["transport"][a+"/"+b]={metric:paired(transport_rows,"transport",a,b,metric) for metric in ("ttft_ms","completion_ms")}
        result["transport_setup"]={arm:{"n":len(values),"median_ms":statistics.median(values)} for arm in ("get_warmed","prewarm_warmed")
            if (values:=[r["completion_ms"] for r in transport_rows if r["phase"]=="setup" and r["arm"]==arm and r.get("completion_ms") is not None])}
    return result


if __name__ == "__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--study",type=Path,required=True)
    parser.add_argument("--transport",type=Path)
    parser.add_argument("--output",type=Path,required=True)
    args=parser.parse_args()
    prewarm.write_json(args.output,analyze(args.study,args.transport))
    print(json.dumps({"analysis_written":True}))
