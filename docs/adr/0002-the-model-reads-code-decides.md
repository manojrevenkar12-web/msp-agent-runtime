# 2. The model reads and drafts; code decides

**Context.** A model is good at reading a German ticket, a technician's shorthand note, and at drafting a polite
message. It is bad at being the last word on money and priority: it obeys instructions planted in a ticket, invents
quotes, misreads "0,75h", and copies internal notes into customer messages. The simulated months put numbers on each.

**Decision.** Every model answer passes a check in code before it changes anything:
- triage evidence must be words that appear in the ticket and describe impact, not demand priority; the category is
  checked against similar resolved tickets; priority comes from the ITIL matrix;
- a piece of work read from a note must be quoted, have a stated duration, be plausible and fall before the note; work
  a measured session or a typed entry already covers is not entered again; long pieces wait for the technician;
- invoices are computed by the billing rules from confirmed entries alone; no model takes part;
- a draft to a customer that repeats an internal note or looks like a credential is replaced by a template.

**Consequences.** The checks cost little recovered revenue against letting the model act directly and remove
almost all of its overbilling, double entries and leaks. They also have edges: the held-out months showed claims of
standing passing the evidence rule. Such findings change the rule and need new held-out months.
