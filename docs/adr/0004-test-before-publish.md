# 4. A version is tested dry before it is published

**Context.** Service providers will want to tune agents (the approval limit, when a note's work needs confirmation).
A setting that looks harmless can change many decisions.

**Decision.** Settings are typed with limits the agent was tested for. A change is a new draft version. The test bench
runs the draft dry (no writes; planned writes and approvals are kept as steps) on the tenant's test cases, which are
past runs kept with their expected outcome, and on the agent's latest real events, reporting every decision that
would change. Publishing requires a passing report for that version; publishing retires the previous version.

**Consequences.** Replays read the systems as they are now, not as they were, so a replay of time capture after the
fact sees its own entries. Test cases therefore check outcome fields an agent declares (`testedFields`), and the
change list is advice, not a gate.
