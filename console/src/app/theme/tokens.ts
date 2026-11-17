/** The drawn parts of the console (the switchboard, the trace) read sizes and colours from here. tokens.css holds the same values. */
export const color = {
  paper: '#f1f4f3',
  sheet: '#ffffff',
  ink: '#131719',
  ink2: '#4c5862',
  rule: '#e2e7e5',
  ruleStrong: '#c9d1ce',
  petrol: '#0d5666',
  petrolWash: '#e3eff1',
  done: '#2e7a4e',
  retry: '#b9740f',
  waiting: '#5947b0',
  failed: '#b3261e',
} as const;

export const board = {
  laneHeight: 56,
  laneGap: 10,
  labelWidth: 210,
  tickWidth: 3,
  tickHeight: 30,
  axisHeight: 28,
  width: 1000,
} as const;

export const trace = {
  rowHeight: 24,
  labelWidth: 220,
  width: 620,
} as const;

export const motion = {
  laneStagger: 0.12,
  tickStagger: 0.004,
  base: 0.28,
  ease: [0.22, 1, 0.36, 1] as [number, number, number, number],
} as const;

/** A status always comes with a word, not only a colour. */
export const statusWord: Record<string, string> = {
  DONE: 'done',
  RUNNING: 'running',
  PENDING: 'queued',
  RETRY: 'retrying',
  WAITING_APPROVAL: 'waiting for a person',
  FAILED: 'failed',
  DEAD: 'gave up',
};

export const statusColor: Record<string, string> = {
  DONE: color.done,
  RUNNING: color.petrol,
  PENDING: color.ink2,
  RETRY: color.retry,
  WAITING_APPROVAL: color.waiting,
  FAILED: color.failed,
  DEAD: color.failed,
};
