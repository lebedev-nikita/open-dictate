#!/usr/bin/env python3
"""Fixed independent replication of the exploratory simultaneous-request anomaly."""

from datetime import datetime, timezone
import hashlib
import importlib.util
import itertools
import json
from pathlib import Path
import random
import sys
import uuid

spec = importlib.util.spec_from_file_location("prewarm", Path(__file__).with_name("evaluate-prewarm.py"))
prewarm = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prewarm)


def run(directory, key, bodies):
    directory.mkdir(parents=True, exist_ok=False)
    arms = ["cold", "completed", "same_concurrent", "unrelated_concurrent"]
    plan = {"version": 1, "blocks": 48, "seed": 20261022, "arms": arms,
            "primary": "same_concurrent versus cold; paired median TTFT saving",
            "reason": "Independent replication after exploratory lead=0 anomaly; not pooled with primary study",
            "order": "All 24 permutations twice, shuffled", "bootstrap_replicates": 10000}
    prewarm.write_json(directory / "plan.json", plan)
    prewarm.write_json(directory / "environment.json", {
        "utc": datetime.now(timezone.utc).isoformat(), "model": bodies["ru_en"]["model"],
        "runner_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        "transport": "Two reused, authenticated HTTP/1.1 TLS connections; same inference transport in all arms"})
    study = prewarm.Study(directory, key, plan, bodies)
    order = list(itertools.permutations(arms))*2
    random.Random(plan["seed"]).shuffle(order)
    study.connection.warm_transport()
    study.preparation.warm_transport()
    try:
        for block, block_order in enumerate(order):
            case = prewarm.SHORT[block % len(prewarm.SHORT)]
            for position, arm in enumerate(block_order):
                nonce = uuid.uuid4().hex
                labels = dict(scope="ru_en", block=block, phase="race", position=position)
                if arm == "completed":
                    study.call("ru_en", block, "preparation_completed", nonce, case,
                               phase="race", prewarm=True, connection=study.preparation)
                future = None
                if arm.endswith("concurrent"):
                    prep_nonce = nonce if arm == "same_concurrent" else uuid.uuid4().hex
                    future = study.executor.submit(study.preparation.request,
                        prewarm.request_body(bodies["ru_en"], prep_nonce, prewarm=True))
                study.record(study.connection.request(prewarm.request_body(bodies["ru_en"], nonce, case[0]), case[1]),
                             arm=arm, **labels)
                if future is not None:
                    study.record(future.result(), arm="preparation_"+arm, **labels)
            if (block+1) % 8 == 0:
                print(prewarm.encode({"replication_blocks_finished":block+1,"requests":len(study.rows)}),flush=True)
        summary={a+"/"+b:{metric:prewarm.paired_summary(study.rows,"race","ru_en",a,b,metric)
                          for metric in ("ttft_ms","completion_ms")}
                 for a,b in [("cold","same_concurrent"),("cold","completed"),
                             ("cold","unrelated_concurrent"),("unrelated_concurrent","same_concurrent")]}
        summary["checks"]={arm:{"n":len(selected),"completed":sum(r["status"]=="completed" for r in selected),
            "cache_hits":sum(r.get("cache_read_tokens",0)>0 for r in selected),
            "valid_json":sum(r.get("valid_json",False) for r in selected),
            "zero_output":sum(r.get("output_tokens")==0 and not r.get("output_chars") and not r.get("text_deltas") for r in selected)}
            for arm in arms+["preparation_completed","preparation_same_concurrent","preparation_unrelated_concurrent"]
            if (selected:=[r for r in study.rows if r["arm"]==arm])}
        prewarm.write_json(directory/"summary.json",summary)
    finally:
        study.executor.shutdown(wait=True)
        study.connection.close()
        study.preparation.close()


if __name__ == "__main__":
    parser = prewarm.argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output",type=Path,required=True)
    parser.add_argument("--env-file",type=Path,default=prewarm.ROOT/".env")
    parser.add_argument("--key-name",choices=["OPENAI_API_KEY","TRANSCRIBE_KEY"],default="OPENAI_API_KEY")
    parser.add_argument("--allow-transcription-key-for-this-run",action="store_true")
    args=parser.parse_args()
    try:
        key=prewarm.load_key(args.env_file,args.key_name,args.allow_transcription_key_for_this_run)
        run(args.output,key,prewarm.templates())
    except (prewarm.StudyError,OSError,ValueError,KeyError,TypeError):
        print("Replication failed; details withheld. Inspect metric-only attempts.",file=sys.stderr)
        sys.exit(1)
