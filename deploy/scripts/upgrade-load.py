#!/usr/bin/env python3
"""Steady traffic for the rolling-upgrade test: it counts every request that does not succeed.

Two feeders keep delivering small batches of real feed lines (a repeated line is a duplicate, not
an error), and four readers keep asking for the map viewport and the track of an asset, as the
console does. It runs until the file given as STOP_FILE exists, then prints one summary line.

    upgrade-load.py BASE_URL FEED_FILE STOP_FILE FEED_USER FEED_PASSWORD READER_USER READER_PASSWORD
"""
import gzip, json, os, sys, threading, time, urllib.error, urllib.request

base, feed_file, stop_file, feed_user, feed_pass, reader_user, reader_pass = sys.argv[1:8]
lock = threading.Lock()
stats = {"ok": 0, "failed": 0, "reasons": {}}


def call(method, path, token=None, body=None, content_type="application/json"):
    headers = {"Content-Type": content_type}
    if token:
        headers["Authorization"] = "Bearer " + token
    data = body.encode() if isinstance(body, str) else body
    req = urllib.request.Request(base + path, data=data, method=method, headers=headers)
    with urllib.request.urlopen(req, timeout=20) as r:
        return r.status, r.read()


def login(user, password):
    _, raw = call("POST", "/api/v1/auth/login", body=json.dumps({"username": user, "password": password}))
    return json.loads(raw)["accessToken"]


def record(ok, reason=None):
    with lock:
        if ok:
            stats["ok"] += 1
        else:
            stats["failed"] += 1
            stats["reasons"][reason] = stats["reasons"].get(reason, 0) + 1


def attempt(*args, **kwargs):
    try:
        status, _ = call(*args, **kwargs)
        record(200 <= status < 300, "HTTP %d" % status)
    except urllib.error.HTTPError as e:
        record(False, "HTTP %d" % e.code)
    except Exception as e:  # connection reset, timeout, refused
        record(False, type(e).__name__)


with gzip.open(feed_file, "rt") as f:
    lines = [next(f).rstrip("\n") for _ in range(20000)]
feed_token = login(feed_user, feed_pass)
read_token = login(reader_user, reader_pass)
assets = sorted({json.loads(l)["assetId"] for l in lines if l.startswith("{") and '"assetId"' in l and l.endswith("}")})[:50]


def stopped():
    return os.path.exists(stop_file)


def feeder(offset):
    i = offset
    while not stopped():
        batch = [lines[(i + k) % len(lines)] for k in range(50)]
        i += 50
        attempt("POST", "/api/v1/reports/batch", feed_token, "\n".join(batch) + "\n", "application/x-ndjson")
        time.sleep(0.1)


def reader(n):
    i = n
    while not stopped():
        attempt("GET", "/api/v1/assets?bbox=-180,-85,180,85&limit=1000", read_token)
        attempt("GET", "/api/v1/assets/%s/track" % assets[i % len(assets)], read_token)
        attempt("GET", "/api/v1/alerts?limit=50", read_token)
        i += 1
        time.sleep(0.2)


threads = [threading.Thread(target=feeder, args=(k * 5000,)) for k in range(2)]
threads += [threading.Thread(target=reader, args=(k,)) for k in range(4)]
for t in threads:
    t.start()
for t in threads:
    t.join()
print("LOAD " + json.dumps(stats))
