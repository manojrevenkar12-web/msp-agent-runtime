# Console design plan

Subject: the operations console of an agent runtime at a German IT service provider (Systemhaus). Users: the
dispatcher, the office lead who approves invoices, the admin who configures agents. Primary job: see what the agents
did and why, approve what waits for a person, change an agent only after testing it.

Colour (cool, calm, like a control room in daylight; status colours carry a word or shape too):
- paper   #F1F4F3  page
- sheet   #FFFFFF  surfaces
- ink     #131719  text
- ink-2   #4C5862  secondary text
- rule    #D4DBD8  lines
- petrol  #0D5666  primary, the runtime itself
- done    #2E7A4E / retry #B9740F / waiting #5947B0 / failed #B3261E

Type: one family, Archivo (variable, width axis 62–125). Headings expanded (wdth 115, weight 640), body normal
(wdth 100), table and figure labels condensed (wdth 82). No monospace, no all-caps labels.

Layout: a narrow left rail (tenant, four destinations, connection); content left-aligned, max 1240px.
Overview opens with the switchboard: one lane per agent across a time axis, every run a tick coloured by status
(retries and waits stand out). Below it, the month compared: status quo / model decides / this runtime.

Motion: one orchestrated moment, the ticks drawing in lane by lane on load; new live runs slide into their lane.
Disclosure motion (opening a run, a test report) shows what changed. Reduced motion respected.

Review against defaults: no cream/terracotta, no dark+acid, no identical rounded cards with soft shadows, no
eyebrow labels, no arrows on links. The memorable element is the switchboard; everything else stays quiet.
