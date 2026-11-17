-- The runtime's state. Every table carries the tenant; every query filters by it.

create table deliveries (
    tenant      text        not null,
    source      text        not null,
    event_id    text        not null,
    first_at    timestamptz not null,
    count       int         not null default 1,
    primary key (tenant, source, event_id)
);

-- Events accepted from webhooks, published to the broker by the relay (transactional outbox).
create table outbox (
    id            bigserial primary key,
    tenant        text        not null,
    partition_key text        not null,
    event         jsonb       not null,
    created_at    timestamptz not null default now(),
    published_at  timestamptz
);
create index outbox_unpublished on outbox (id) where published_at is null;

create table runs (
    tenant          text        not null,
    id              text        not null,
    agent_id        text        not null,
    agent_version   int         not null,
    event           jsonb       not null,
    status          text        not null,
    attempts        int         not null,
    next_attempt_at timestamptz,
    lease_owner     text,
    lease_until     timestamptz,
    created_at      timestamptz not null,
    updated_at      timestamptz not null,
    error           text,
    dry_run         boolean     not null default false,
    primary key (tenant, id)
);
create index runs_due on runs (status, next_attempt_at) where status in ('PENDING', 'RETRY', 'RUNNING');
create index runs_recent on runs (tenant, created_at desc);

create table steps (
    run_id text        not null,
    seq    int         not null,
    name   text        not null,
    json   jsonb       not null,
    at     timestamptz not null,
    primary key (run_id, name)
);

create table effects (
    tenant text        not null,
    key    text        not null,
    run_id text        not null,
    tool   text        not null,
    state  text        not null,
    at     timestamptz not null,
    primary key (tenant, key)
);

create table approvals (
    id          text primary key,
    tenant      text        not null,
    run_id      text        not null,
    agent_id    text        not null,
    name        text        not null,
    reason      text        not null,
    payload     jsonb       not null,
    status      text        not null,
    decided_by  text,
    created_at  timestamptz not null,
    decided_at  timestamptz,
    unique (run_id, name)
);
create index approvals_pending on approvals (tenant) where status = 'PENDING';

create table spans (
    id         bigserial primary key,
    tenant     text        not null,
    run_id     text        not null,
    trace_id   text        not null,
    span_id    text        not null,
    parent_id  text,
    name       text        not null,
    start_at   timestamptz not null,
    end_at     timestamptz not null,
    attributes jsonb       not null,
    error      boolean     not null,
    exported   boolean     not null default false
);
create index spans_run on spans (tenant, run_id);
create index spans_unexported on spans (id) where exported = false;

create table agent_versions (
    tenant     text        not null,
    agent_id   text        not null,
    version    int         not null,
    config     jsonb       not null,
    status     text        not null,
    created_by text        not null,
    created_at timestamptz not null,
    note       text,
    primary key (tenant, agent_id, version)
);
create unique index one_published_version on agent_versions (tenant, agent_id) where status = 'PUBLISHED';

-- Cases a version must pass before it is published, and the test bench's last report per version.
create table test_cases (
    tenant   text  not null,
    agent_id text  not null,
    id       text  not null,
    event    jsonb not null,
    expected jsonb not null,
    note     text,
    primary key (tenant, agent_id, id)
);

create table test_reports (
    tenant   text        not null,
    agent_id text        not null,
    version  int         not null,
    report   jsonb       not null,
    passed   boolean     not null,
    at       timestamptz not null,
    primary key (tenant, agent_id, version)
);

-- Resolved tickets for similarity search. V2 adds a pgvector column and index when the extension is there.
create table ticket_history (
    tenant    text    not null,
    ticket_id text    not null,
    category  text    not null,
    priority  text    not null,
    embedding real[]  not null,
    primary key (tenant, ticket_id)
);
