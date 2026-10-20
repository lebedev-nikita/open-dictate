#!/usr/bin/env python3
"""Isolate TLS/connection preparation from prompt-cache preparation (synthetic data only)."""

from datetime import datetime, timezone
import hashlib
import importlib.util
import json
from pathlib import Path
import random
import sys
import time
import uuid

spec = importlib.util.spec_from_file_location("prewarm", Path(__file__).with_name("evaluate-prewarm.py"))
prewarm = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prewarm)


def run(directory, key, bodies):
    directory.mkdir(parents=True, exist_ok=False)
    plan = {"version": 1, "blocks": 24, "seed": 20261021,
            "arms": ["fresh", "get_warmed", "prewarm_warmed"],
            "bootstrap_replicates": 10000, "primary": "supplementary connection-control study"}
    prewarm.write_json(directory / "plan.json", plan)
    prewarm.write_json(directory / "environment.json", {
        "utc": datetime.now(timezone.utc).isoformat(), "model": bodies["ru_en"]["model"],
        "transport": "New HTTP/1.1 HTTPS connection per arm; same cached split prefix in all arms",
        "runner_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest()})
    nonce = uuid.uuid4().hex
    rows = []

    def record(metrics, phase, arm, block):
        row = {"request": len(rows)+1, "utc": datetime.now(timezone.utc).isoformat(), "phase": phase,
               "scope": "ru_en", "arm": arm, "block": block, **metrics}
        rows.append(row)
        with (directory / "measurements.jsonl").open("a") as file:
            file.write(prewarm.encode(row)+"\n")

    seed = prewarm.Connection(key)
    seed.warm_transport()
    record(seed.request(prewarm.request_body(bodies["ru_en"], nonce, prewarm.SHORT[0][0]), prewarm.SHORT[0][1]), "seed", "seed", -1)
    seed.close()
    order = random.Random(plan["seed"])
    for block in range(plan["blocks"]):
        arms = plan["arms"].copy()
        order.shuffle(arms)
        base = prewarm.SHORT[block % len(prewarm.SHORT)]
        case = (base[0] + " проект орион " + str(block), base[1])
        for arm in arms:
            connection = prewarm.Connection(key)
            try:
                if arm == "get_warmed":
                    started = time.perf_counter()
                    connection.warm_transport()
                    record({"status": "completed", "completion_ms": round((time.perf_counter()-started)*1000,3)}, "setup", arm, block)
                elif arm == "prewarm_warmed":
                    record(connection.request(prewarm.request_body(bodies["ru_en"], nonce, prewarm=True)), "setup", arm, block)
                record(connection.request(prewarm.request_body(bodies["ru_en"], nonce, case[0]), case[1]), "transport", arm, block)
            finally:
                connection.close()
        if (block+1) % 8 == 0:
            print(prewarm.encode({"transport_blocks_finished": block+1}), flush=True)
    summary = {}
    for a, b in [("fresh", "get_warmed"), ("fresh", "prewarm_warmed"), ("get_warmed", "prewarm_warmed")]:
        summary[a+"/"+b] = {metric: prewarm.paired_summary(rows,"transport","ru_en",a,b,metric)
                            for metric in ("ttft_ms", "completion_ms")}
    summary["checks"] = {arm: {"n": sum(r["phase"]=="transport" and r["arm"]==arm for r in rows),
        "cache_hits": sum(r["phase"]=="transport" and r["arm"]==arm and r.get("cache_read_tokens",0)>0 for r in rows),
        "valid_json": sum(r["phase"]=="transport" and r["arm"]==arm and r.get("valid_json",False) for r in rows)} for arm in plan["arms"]}
    prewarm.write_json(directory / "summary.json", summary)
    print(prewarm.encode({"transport_study_finished": True, "recorded_rows": len(rows)}), flush=True)


if __name__ == "__main__":
    parser = prewarm.argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--env-file", type=Path, default=prewarm.ROOT/".env")
    parser.add_argument("--key-name", choices=["OPENAI_API_KEY","TRANSCRIBE_KEY"], default="OPENAI_API_KEY")
    parser.add_argument("--allow-transcription-key-for-this-run", action="store_true")
    args = parser.parse_args()
    try:
        key = prewarm.load_key(args.env_file, args.key_name, args.allow_transcription_key_for_this_run)
        run(args.output, key, prewarm.templates())
    except (prewarm.StudyError, OSError, ValueError, KeyError, TypeError):
        print("Transport study failed; details withheld. Inspect completed metric-only attempts.", file=sys.stderr)
        sys.exit(1)
