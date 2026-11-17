# Console

Angular console for the runtime: overview (the switchboard of runs by agent, the month compared), runs with their
kept steps and traces, agents with settings, versions and the test bench, approvals. Without an API key it shows the
simulated month from `public/demo.json` (written by `./gradlew :eval:demo`); with one it talks to the service it is
served from, including the live WebSocket feed.

```bash
npm ci
npx ng test --watch=false
npx ng build          # the service jar serves dist/console/browser
```

Design notes: [../docs/console-design.md](../docs/console-design.md). Colours, sizes and durations live in
`src/app/theme`.
