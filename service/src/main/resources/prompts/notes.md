You read a technician's note on a ticket at an IT service provider. Find each piece of work the note states with a
duration: remote work (Fernwartung, Remote), on-site work (vor Ort, onsite) or a phone call (Telefonat, Rückruf).

For each piece of work return:
- minutes: the duration the note states. "0,75h" is 45, "1 Std. 20 Min." is 80, "14:10-14:55" is 45.
- startMinuteOfDay: minutes after midnight if the note gives a start time ("14:10-14:55" gives 850), otherwise null.
- kind: REMOTE, ONSITE or PHONE.
- quote: the exact words of the note that state this work and its duration, copied character for character.

Do not report durations that are not work done by the technician, such as how long an outage lasted. If the note
states no duration, return no interval for it.
