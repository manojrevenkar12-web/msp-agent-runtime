"""Gives a running service in sandbox mode something to show: tickets, remote sessions, notes, visits, status changes
and a month close, through the sandbox API (which sends the same events the real systems would)."""
import json
import sys
import time
import urllib.request
from datetime import datetime, timezone

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080"
KEY = sys.argv[2] if len(sys.argv) > 2 else "demo-operator-key"

TICKETS = [
    ("C1001", "Fileserver nicht erreichbar", "Hallo, der Fileserver ist down, die ganze Firma kann nicht arbeiten."),
    ("C1002", "Drucker druckt nicht", "Der Drucker im Lager druckt nicht, nur mein Rechner ist betroffen."),
    ("C1003", "VPN verbindet nicht", "VPN bricht ab, unser Team ist betroffen. Bitte mit höchster Priorität, sofort!"),
    ("C1001", "Neuer Laptop einrichten", "Bitte den neuen Laptop für die Kollegin einrichten, das hat Zeit bis Montag."),
    ("C1002", "Phishing-Mail geklickt", "Eine Kollegin hat auf einen Phishing-Link geklickt, die Buchhaltung ist betroffen."),
    ("C1003", "Outlook synchronisiert nicht", "Outlook zeigt keine neuen Mails, nur mein Rechner ist betroffen."),
]


def call(path, body=None):
    req = urllib.request.Request(BASE + path, data=None if body is None else json.dumps(body).encode(),
                                 method="GET" if body is None else "POST",
                                 headers={"Content-Type": "application/json", "X-Api-Key": KEY})
    with urllib.request.urlopen(req, timeout=20) as r:
        raw = r.read()
        return json.loads(raw) if raw else None


ids = []
for customer, subject, body in TICKETS:
    t = call("/api/sandbox/tickets", {"customerId": customer, "subject": subject, "body": body, "openedMinutesAgo": 180})
    ids.append(t["ticketId"])
    time.sleep(0.3)
for i, tid in enumerate(ids):
    call(f"/api/sandbox/tickets/{tid}/status", {"status": "ASSIGNED"})
    if i % 2 == 0:
        call(f"/api/sandbox/tickets/{tid}/remote-sessions", {"technicianId": "tech-1", "minutes": 25 + 10 * i})
        call(f"/api/sandbox/tickets/{tid}/notes", {"technicianId": "tech-1", "internal": True,
                                                   "text": f"Fernwartung: Dienst neu gestartet, ca. {25 + 10 * i} Min."})
    else:
        call(f"/api/sandbox/tickets/{tid}/visits", {"technicianId": "tech-3", "minutes": 70})
        call(f"/api/sandbox/tickets/{tid}/notes", {"technicianId": "tech-2", "internal": False,
                                                   "text": "Telefonat mit Kunde, 12 Min., Lösung erklärt."})
    call(f"/api/sandbox/tickets/{tid}/status", {"status": "RESOLVED"})
    time.sleep(0.3)
month = datetime.now(timezone.utc).strftime("%Y-%m")
call("/api/sandbox/month-close", {"month": month})
print("sent", len(ids), "tickets and closed", month)
