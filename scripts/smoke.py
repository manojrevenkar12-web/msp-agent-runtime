"""Smoke test of a running service: opens a sandbox ticket, sends a signed webhook, and waits for the agents."""
import hashlib
import hmac
import json
import sys
import time
import urllib.request

BASE = sys.argv[1]
KEY = "demo-operator-key"
SECRET = b"demo-webhook-secret"


def call(method, path, body=None, headers=None):
    data = None if body is None else json.dumps(body).encode()
    h = {"Content-Type": "application/json", "X-Api-Key": KEY}
    h.update(headers or {})
    req = urllib.request.Request(BASE + path, data=data, method=method, headers=h)
    with urllib.request.urlopen(req, timeout=10) as r:
        raw = r.read()
        return r.status, json.loads(raw) if raw else None


status, t = call("POST", "/api/sandbox/tickets", {"customerId": "C1001", "subject": "Server down",
                                                   "body": "Der Server ist down, die ganze Firma kann nicht arbeiten."})
ticket = t["ticketId"]
body = json.dumps({"id": "smoke-" + ticket, "type": "ticket.status_changed",
                   "data": {"ticketId": ticket, "status": "ASSIGNED"}}).encode()
sig = "sha256=" + hmac.new(SECRET, body, hashlib.sha256).hexdigest()
req = urllib.request.Request(BASE + "/hooks/systemhaus-nord/tickets", data=body, method="POST",
                             headers={"Content-Type": "application/json", "X-Msp-Signature": sig})
with urllib.request.urlopen(req, timeout=10) as r:
    assert r.status == 202, r.status

done = set()
for _ in range(60):
    _, runs = call("GET", "/api/runs?limit=50")
    done = {r["agentId"] for r in runs if r["subject"] == ticket and r["status"] == "DONE"}
    if {"triage", "concierge"} <= done:
        break
    time.sleep(1)
print("agents done for", ticket, sorted(done))
assert {"triage", "concierge"} <= done, done
