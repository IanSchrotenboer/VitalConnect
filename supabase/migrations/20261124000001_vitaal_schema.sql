-- Vitaal: datamodel voor gezondheidsdata uit Garmin Connect (cloud) en Health Connect (Android)
-- Gebruikersdata is alleen leesbaar voor de eigenaar (RLS). Schrijven gaat via
-- edge functions (service role) of de ingest-RPC, zodat waarden altijd gevalideerd worden.

-- 1. Catalogus --------------------------------------------------------------
create table public.metrics (
  id text primary key,
  name_nl text not null,
  unit text not null default '',
  aggregation text not null check (aggregation in ('sum','avg','last')),
  lower_is_better boolean not null default false,
  min_value numeric not null,
  max_value numeric not null
);

insert into public.metrics (id, name_nl, unit, aggregation, lower_is_better, min_value, max_value) values
  ('steps',  'Stappen',            '',          'sum',  false, 0, 150000),
  ('active', 'Actieve minuten',    'min',       'sum',  false, 0, 1440),
  ('kcal',   'Actieve calorieën',  'kcal',      'sum',  false, 0, 20000),
  ('dist',   'Afstand',            'km',        'sum',  false, 0, 500),
  ('floors', 'Verdiepingen',       '',          'sum',  false, 0, 1000),
  ('rhr',    'Rusthartslag',       'bpm',       'avg',  true,  20, 200),
  ('hrv',    'HRV',                'ms',        'avg',  false, 1, 400),
  ('spo2',   'Zuurstof (SpO2)',    '%',         'avg',  false, 50, 100),
  ('sleep',  'Slaapduur',          'u',         'sum',  false, 0, 24),
  ('sscore', 'Slaapscore',         '',          'last', false, 0, 100),
  ('resp',   'Ademhaling',         '/min',      'avg',  true,  4, 60),
  ('stress', 'Stress',             '',          'avg',  true,  0, 100),
  ('energy', 'Energieniveau',      '%',         'last', false, 0, 100),
  ('recov',  'Hersteltijd',        'u',         'last', true,  0, 168),
  ('vo2',    'VO2max',             'ml/kg/min', 'last', false, 10, 100),
  ('weight', 'Gewicht',            'kg',        'last', true,  20, 400);

create table public.providers (
  id text primary key,
  name text not null,
  priority int not null unique  -- lager = voorrang bij gelijke metric en dag
);
insert into public.providers (id, name, priority) values
  ('garmin', 'Garmin Connect', 1),
  ('health_connect', 'Health Connect', 2);

-- 2. Gebruiker --------------------------------------------------------------
create table public.profiles (
  id uuid primary key references auth.users (id) on delete cascade,
  display_name text,
  timezone text not null default 'Europe/Amsterdam',
  consent_health_at timestamptz,          -- expliciete AVG-toestemming (art. 9)
  consent_version text,
  created_at timestamptz not null default now()
);

create table public.connections (
  user_id uuid not null references auth.users (id) on delete cascade,
  provider text not null references public.providers (id),
  external_user_id text,
  status text not null default 'active' check (status in ('active','revoked','error')),
  connected_at timestamptz not null default now(),
  last_data_at timestamptz,
  primary key (user_id, provider),
  unique (provider, external_user_id)
);

create table public.metric_prefs (
  user_id uuid not null references auth.users (id) on delete cascade,
  metric text not null references public.metrics (id),
  visible boolean not null default true,
  position int not null default 0,
  size text not null default 's' check (size in ('s','l')),
  preferred_source text,                  -- 'garmin', 'health_connect' of 'health_connect:<package>'
  goal numeric check (goal > 0),
  in_rings boolean not null default false,
  primary key (user_id, metric)
);

-- 3. Data -------------------------------------------------------------------
create table public.daily_metrics (
  user_id uuid not null references auth.users (id) on delete cascade,
  day date not null,
  metric text not null references public.metrics (id),
  source text not null,                   -- 'garmin' of 'health_connect:<package van de schrijvende app>'
  provider text not null references public.providers (id),
  value numeric not null,
  updated_at timestamptz not null default now(),
  primary key (user_id, day, metric, source)
);
create index daily_metrics_user_metric_day on public.daily_metrics (user_id, metric, day desc);

-- 4. Interne tabellen: alleen service role (edge functions) ----------------
create table public.garmin_tokens (
  user_id uuid primary key references auth.users (id) on delete cascade,
  access_token_enc text not null,         -- AES-GCM versleuteld in de edge function
  refresh_token_enc text not null,
  access_expires_at timestamptz not null,
  refresh_expires_at timestamptz,
  scope text,
  updated_at timestamptz not null default now()
);

create table public.oauth_states (
  state text primary key,
  user_id uuid not null references auth.users (id) on delete cascade,
  code_verifier text not null,
  created_at timestamptz not null default now()
);

create table public.garmin_raw (
  id bigint generated always as identity primary key,
  user_id uuid references auth.users (id) on delete cascade,
  summary_type text not null,
  summary_id text,
  payload jsonb not null,
  received_at timestamptz not null default now()
);
create index garmin_raw_received on public.garmin_raw (received_at);

-- 5. Row level security -----------------------------------------------------
alter table public.metrics enable row level security;
alter table public.providers enable row level security;
alter table public.profiles enable row level security;
alter table public.connections enable row level security;
alter table public.metric_prefs enable row level security;
alter table public.daily_metrics enable row level security;
alter table public.garmin_tokens enable row level security;
alter table public.oauth_states enable row level security;
alter table public.garmin_raw enable row level security;

create policy "catalogus leesbaar" on public.metrics for select to authenticated using (true);
create policy "providers leesbaar" on public.providers for select to authenticated using (true);

create policy "eigen profiel lezen" on public.profiles for select to authenticated using ((select auth.uid()) = id);
create policy "eigen profiel wijzigen" on public.profiles for update to authenticated using ((select auth.uid()) = id) with check ((select auth.uid()) = id);

create policy "eigen koppelingen lezen" on public.connections for select to authenticated using ((select auth.uid()) = user_id);

create policy "eigen voorkeuren lezen" on public.metric_prefs for select to authenticated using ((select auth.uid()) = user_id);
create policy "eigen voorkeuren toevoegen" on public.metric_prefs for insert to authenticated with check ((select auth.uid()) = user_id);
create policy "eigen voorkeuren wijzigen" on public.metric_prefs for update to authenticated using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
create policy "eigen voorkeuren verwijderen" on public.metric_prefs for delete to authenticated using ((select auth.uid()) = user_id);

create policy "eigen data lezen" on public.daily_metrics for select to authenticated using ((select auth.uid()) = user_id);
create policy "eigen data verwijderen" on public.daily_metrics for delete to authenticated using ((select auth.uid()) = user_id);

-- Interne tabellen: geen policies en geen rechten voor app-gebruikers.
revoke all on public.garmin_tokens, public.oauth_states, public.garmin_raw from anon, authenticated;
revoke all on public.metrics, public.providers, public.profiles, public.connections, public.metric_prefs, public.daily_metrics from anon;
grant select on public.metrics, public.providers, public.connections, public.daily_metrics to authenticated;
grant select, update on public.profiles to authenticated;
grant select, insert, update, delete on public.metric_prefs to authenticated;
grant delete on public.daily_metrics to authenticated;

-- 6. Triggers ---------------------------------------------------------------
create function public.handle_new_user() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  insert into public.profiles (id) values (new.id);
  insert into public.metric_prefs (user_id, metric, visible, position, size, goal, in_rings) values
    (new.id, 'steps',  true, 0, 'l', 10000, true),
    (new.id, 'sleep',  true, 1, 'l', 8,     true),
    (new.id, 'rhr',    true, 2, 's', null,  false),
    (new.id, 'active', true, 3, 's', 30,    true),
    (new.id, 'hrv',    true, 4, 's', null,  false),
    (new.id, 'energy', true, 5, 's', null,  false),
    (new.id, 'kcal',   true, 6, 's', 600,   false),
    (new.id, 'weight', true, 7, 's', null,  false);
  return new;
end $$;

create trigger on_auth_user_created after insert on auth.users
  for each row execute function public.handle_new_user();

create function public.check_rings() returns trigger
language plpgsql set search_path = '' as $$
begin
  if new.in_rings and new.goal is null then
    raise exception 'Een dagring heeft een doel nodig';
  end if;
  if new.in_rings and (
    select count(*) from public.metric_prefs
    where user_id = new.user_id and in_rings and metric <> new.metric
  ) >= 3 then
    raise exception 'Maximaal drie dagringen';
  end if;
  return new;
end $$;

create trigger metric_prefs_rings before insert or update on public.metric_prefs
  for each row execute function public.check_rings();

-- 7. Functies voor de app ---------------------------------------------------
-- Eén waarde per dag en metric: voorkeursbron van de gebruiker, anders providerprioriteit.
create function public.resolved_daily(p_from date, p_to date)
returns table (day date, metric text, value numeric, source text)
language sql stable security invoker set search_path = '' as $$
  select distinct on (d.day, d.metric) d.day, d.metric, d.value, d.source
  from public.daily_metrics d
  join public.providers p on p.id = d.provider
  left join public.metric_prefs mp on mp.user_id = d.user_id and mp.metric = d.metric
  where d.user_id = (select auth.uid())
    and d.day between p_from and p_to
  order by d.day, d.metric,
    (mp.preferred_source is not null and (d.source = mp.preferred_source or d.provider = mp.preferred_source)) desc,
    p.priority,
    d.updated_at desc
$$;

-- Upload vanuit de Android-app: dagwaarden per metric per bron-app in Health Connect.
-- p_rows: [{"day":"2026-11-23","metric":"steps","origin":"com.garmin.android.apps.connectmobile","value":9812}, ...]
create function public.ingest_health_connect(p_rows jsonb)
returns integer
language plpgsql security definer set search_path = '' as $$
declare
  v_uid uuid := auth.uid();
  v_count integer;
begin
  if v_uid is null then
    raise exception 'Niet ingelogd';
  end if;
  if jsonb_typeof(p_rows) <> 'array' or jsonb_array_length(p_rows) > 10000 then
    raise exception 'Ongeldige payload';
  end if;

  with r as (
    select (e->>'day')::date as day, e->>'metric' as metric,
           e->>'origin' as origin, (e->>'value')::numeric as value
    from jsonb_array_elements(p_rows) e
  ), ok as (
    select distinct on (r.day, r.metric, r.origin) r.*
    from r join public.metrics m on m.id = r.metric
    where r.day between current_date - 800 and current_date + 1
      and r.origin ~ '^[A-Za-z0-9_.]{1,200}$'
      and r.value between m.min_value and m.max_value
  )
  insert into public.daily_metrics (user_id, day, metric, source, provider, value, updated_at)
  select v_uid, day, metric, 'health_connect:' || origin, 'health_connect', value, now() from ok
  on conflict (user_id, day, metric, source)
  do update set value = excluded.value, updated_at = now();
  get diagnostics v_count = row_count;

  insert into public.connections (user_id, provider, status, last_data_at)
  values (v_uid, 'health_connect', 'active', now())
  on conflict (user_id, provider) do update set status = 'active', last_data_at = now();

  return v_count;
end $$;

revoke execute on function public.ingest_health_connect(jsonb) from public, anon;
grant execute on function public.ingest_health_connect(jsonb) to authenticated;
revoke execute on function public.resolved_daily(date, date) from public, anon;
grant execute on function public.resolved_daily(date, date) to authenticated;
revoke execute on function public.handle_new_user() from public, anon, authenticated;
revoke execute on function public.check_rings() from public, anon, authenticated;
