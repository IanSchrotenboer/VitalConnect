-- Vitaal: volledige inrichting
-- * Google Health als derde bron
-- * OAuth-tokens en -states per provider (Garmin, Google Health)
-- * Synchronisatielog, geplande taken (pg_cron + pg_net) en bewaartermijnen
-- * Profiel gevuld vanuit auth (e-mail, naam), updated_at-triggers, kolomrechten
-- * AVG: export van alle eigen data

create extension if not exists pg_cron;
create extension if not exists pg_net with schema extensions;

-- 1. Providers ---------------------------------------------------------------
update public.providers set priority = 3 where id = 'health_connect';
insert into public.providers (id, name, priority) values ('google_health', 'Google Health', 2);

alter table public.connections
  add column last_sync_at timestamptz,
  add column last_error text;

-- 2. Tokens per provider (vervangt garmin_tokens) --------------------------
create table public.oauth_tokens (
  user_id uuid not null references auth.users (id) on delete cascade,
  provider text not null references public.providers (id),
  access_token_enc text not null,          -- AES-GCM, sleutel alleen in edge functions
  refresh_token_enc text,
  access_expires_at timestamptz not null,
  refresh_expires_at timestamptz,
  scope text,
  updated_at timestamptz not null default now(),
  primary key (user_id, provider)
);
insert into public.oauth_tokens (user_id, provider, access_token_enc, refresh_token_enc,
                                 access_expires_at, refresh_expires_at, scope, updated_at)
  select user_id, 'garmin', access_token_enc, refresh_token_enc,
         access_expires_at, refresh_expires_at, scope, updated_at
  from public.garmin_tokens;
drop table public.garmin_tokens;
alter table public.oauth_tokens enable row level security;
revoke all on public.oauth_tokens from anon, authenticated;

alter table public.oauth_states
  add column provider text not null default 'garmin' references public.providers (id);

alter table public.garmin_raw rename to provider_raw;
alter index public.garmin_raw_received rename to provider_raw_received;
alter table public.provider_raw
  add column provider text not null default 'garmin' references public.providers (id);

-- 3. Synchronisatielog -------------------------------------------------------
create table public.sync_runs (
  id bigint generated always as identity primary key,
  user_id uuid references auth.users (id) on delete cascade,
  provider text not null references public.providers (id),
  trigger text not null check (trigger in ('cron', 'user', 'webhook', 'app')),
  started_at timestamptz not null default now(),
  finished_at timestamptz,
  status text not null default 'running' check (status in ('running', 'ok', 'error')),
  rows_written integer not null default 0,
  error text
);
create index sync_runs_user_started on public.sync_runs (user_id, started_at desc);
alter table public.sync_runs enable row level security;
create policy "eigen syncs lezen" on public.sync_runs
  for select to authenticated using ((select auth.uid()) = user_id);
revoke all on public.sync_runs from anon, authenticated;
grant select on public.sync_runs to authenticated;

-- 4. Interne geheimen (alleen service role en cron) -------------------------
create table public.app_secrets (
  name text primary key,
  value text not null,
  created_at timestamptz not null default now()
);
alter table public.app_secrets enable row level security;
revoke all on public.app_secrets from anon, authenticated;
insert into public.app_secrets (name, value)
  values ('cron_secret', encode(extensions.gen_random_bytes(32), 'hex'));

-- 5. Profiel en updated_at --------------------------------------------------
alter table public.profiles
  add column email text,
  add column updated_at timestamptz not null default now();
update public.profiles p set email = u.email from auth.users u where u.id = p.id;

alter table public.metric_prefs add column updated_at timestamptz not null default now();

create function public.touch_updated_at() returns trigger
language plpgsql set search_path = '' as $$
begin
  new.updated_at := now();
  return new;
end $$;
create trigger profiles_touch before update on public.profiles
  for each row execute function public.touch_updated_at();
create trigger metric_prefs_touch before update on public.metric_prefs
  for each row execute function public.touch_updated_at();

create or replace function public.handle_new_user() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  insert into public.profiles (id, email, display_name)
  values (new.id, new.email,
          coalesce(new.raw_user_meta_data->>'full_name', new.raw_user_meta_data->>'name'));
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

create function public.handle_user_email_changed() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  update public.profiles set email = new.email where id = new.id;
  return new;
end $$;
create trigger on_auth_user_email_changed after update of email on auth.users
  for each row when (old.email is distinct from new.email)
  execute function public.handle_user_email_changed();

-- Gebruikers mogen alleen deze profielvelden zelf wijzigen
revoke update on public.profiles from authenticated;
grant update (display_name, timezone, consent_health_at, consent_version) on public.profiles to authenticated;

-- 6. Upload vanuit Health Connect: ook loggen ------------------------------
create or replace function public.ingest_health_connect(p_rows jsonb)
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

  insert into public.connections (user_id, provider, status, last_data_at, last_sync_at, last_error)
  values (v_uid, 'health_connect', 'active', now(), now(), null)
  on conflict (user_id, provider)
  do update set status = 'active', last_data_at = now(), last_sync_at = now(), last_error = null;

  insert into public.sync_runs (user_id, provider, trigger, finished_at, status, rows_written)
  values (v_uid, 'health_connect', 'app', now(), 'ok', v_count);

  return v_count;
end $$;
revoke execute on function public.ingest_health_connect(jsonb) from public, anon;
grant execute on function public.ingest_health_connect(jsonb) to authenticated;

-- 7. AVG: alle eigen data in één JSON --------------------------------------
create function public.export_my_data() returns jsonb
language sql stable security invoker set search_path = '' as $$
  select jsonb_build_object(
    'exported_at', now(),
    'profile', (select to_jsonb(p) from public.profiles p where p.id = (select auth.uid())),
    'connections', coalesce((select jsonb_agg(to_jsonb(c) - 'user_id')
                             from public.connections c where c.user_id = (select auth.uid())), '[]'::jsonb),
    'dashboard', coalesce((select jsonb_agg(to_jsonb(m) - 'user_id' order by m.position)
                           from public.metric_prefs m where m.user_id = (select auth.uid())), '[]'::jsonb),
    'daily_metrics', coalesce((select jsonb_agg(jsonb_build_object('day', d.day, 'metric', d.metric,
                                 'source', d.source, 'value', d.value) order by d.day, d.metric)
                               from public.daily_metrics d where d.user_id = (select auth.uid())), '[]'::jsonb),
    'sync_runs', coalesce((select jsonb_agg(to_jsonb(s) - 'user_id' order by s.started_at desc)
                           from public.sync_runs s where s.user_id = (select auth.uid())), '[]'::jsonb)
  )
$$;
revoke execute on function public.export_my_data() from public, anon;
grant execute on function public.export_my_data() to authenticated;

revoke execute on function public.touch_updated_at() from public, anon, authenticated;
revoke execute on function public.handle_user_email_changed() from public, anon, authenticated;
revoke execute on function public.handle_new_user() from public, anon, authenticated;

-- 8. Geplande taken -----------------------------------------------------------
-- Elk uur Google Health ophalen (Google Health API is pull-based)
select cron.schedule('google-health-sync', '17 * * * *', $cron$
  select net.http_post(
    url := 'https://qtythirnpokfptchavgy.supabase.co/functions/v1/google-health-sync',
    headers := jsonb_build_object(
      'Content-Type', 'application/json',
      'x-cron-secret', (select value from public.app_secrets where name = 'cron_secret')),
    body := '{}'::jsonb,
    timeout_milliseconds := 120000)
$cron$);

-- Elke nacht opruimen volgens de bewaartermijnen
select cron.schedule('vitaal-cleanup', '43 3 * * *', $cron$
  delete from public.oauth_states where created_at < now() - interval '1 hour';
  delete from public.provider_raw where received_at < now() - interval '90 days';
  update public.sync_runs set status = 'error', error = 'timeout', finished_at = now()
    where status = 'running' and started_at < now() - interval '1 hour';
  delete from public.sync_runs where started_at < now() - interval '60 days';
$cron$);
