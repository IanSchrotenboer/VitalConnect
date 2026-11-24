-- Schaal (v1.7.0): klaar voor veel gebruikers.
-- 1. Statistieken over de hele geschiedenis worden per gebruiker bewaard en alleen opnieuw berekend
--    als er nieuwe data is (of een nieuwe dag begint), in plaats van bij elke keer openen.
-- 2. Eén verzoek (app_state) levert alles wat de app bij het openen nodig heeft.
-- 3. Geen logregel meer per geslaagde synchronisatie; alleen fouten worden bewaard (14 dagen).
-- 4. Servertaak voor Google Health in porties (shards), met index; ongebruikte indexen weg.

-- ---------- 1. Cache voor statistieken ----------
alter table public.profiles add column if not exists data_changed_at timestamptz not null default now();
comment on column public.profiles.data_changed_at is 'Laatste wijziging in de dagwaarden of de voorkeursbronnen; maakt de statistieken-cache ongeldig';

create table if not exists public.metric_stats_cache (
  user_id uuid primary key references auth.users (id) on delete cascade,
  stats jsonb not null,
  computed_at timestamptz not null default now()
);
alter table public.metric_stats_cache enable row level security;  -- alleen via metric_stats()
revoke all on public.metric_stats_cache from anon, authenticated;

create or replace function public.mark_data_changed_new() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  update public.profiles p set data_changed_at = clock_timestamp()
  where p.id in (select distinct n.user_id from new_rows n);
  return null;
end $$;

create or replace function public.mark_data_changed_old() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  update public.profiles p set data_changed_at = clock_timestamp()
  where p.id in (select distinct o.user_id from old_rows o);
  return null;
end $$;

drop trigger if exists daily_metrics_changed_ins on public.daily_metrics;
drop trigger if exists daily_metrics_changed_upd on public.daily_metrics;
drop trigger if exists daily_metrics_changed_del on public.daily_metrics;
create trigger daily_metrics_changed_ins after insert on public.daily_metrics
  referencing new table as new_rows for each statement execute function public.mark_data_changed_new();
create trigger daily_metrics_changed_upd after update on public.daily_metrics
  referencing new table as new_rows for each statement execute function public.mark_data_changed_new();
create trigger daily_metrics_changed_del after delete on public.daily_metrics
  referencing old table as old_rows for each statement execute function public.mark_data_changed_old();
-- Een andere voorkeursbron verandert welke waarde telt
create or replace function public.mark_source_changed() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  update public.profiles set data_changed_at = clock_timestamp() where id = new.user_id;
  return null;
end $$;
drop trigger if exists metric_prefs_source_changed on public.metric_prefs;
create trigger metric_prefs_source_changed after update of preferred_source on public.metric_prefs
  for each row when (old.preferred_source is distinct from new.preferred_source)
  execute function public.mark_source_changed();
revoke all on function public.mark_source_changed() from public, anon, authenticated;

revoke all on function public.mark_data_changed_new() from public, anon, authenticated;
revoke all on function public.mark_data_changed_old() from public, anon, authenticated;

drop function if exists public.metric_stats();
create function public.metric_stats()
returns table (metric text, days bigint, first_day date, last_day date, last_value numeric,
               avg_all numeric, min_all numeric, max_all numeric, total_all numeric, avg_30 numeric)
language plpgsql security definer set search_path = '' as $$
declare
  v_uid uuid := auth.uid();
  v_cache public.metric_stats_cache;
  v_changed timestamptz;
  v_stats jsonb;
begin
  if v_uid is null then return; end if;
  select * into v_cache from public.metric_stats_cache c where c.user_id = v_uid;
  select p.data_changed_at into v_changed from public.profiles p where p.id = v_uid;
  -- Geldig zolang er geen nieuwe data is en het nog dezelfde dag is (avg_30 schuift mee met de datum)
  if v_cache.user_id is not null and v_cache.computed_at >= coalesce(v_changed, '-infinity')
     and v_cache.computed_at::date = current_date then
    v_stats := v_cache.stats;
  else
    with r as (
      select distinct on (d.metric, d.day) d.metric, d.day, d.value
      from public.daily_metrics d
      join public.providers pr on pr.id = d.provider
      left join public.metric_prefs mp on mp.user_id = d.user_id and mp.metric = d.metric
      where d.user_id = v_uid
      order by d.metric, d.day,
        (mp.preferred_source is not null and (d.source = mp.preferred_source or d.provider = mp.preferred_source)) desc,
        pr.priority, d.updated_at desc
    ), s as (
      select r.metric, count(*) as days, min(r.day) as first_day, max(r.day) as last_day,
             (array_agg(r.value order by r.day desc))[1] as last_value,
             round(avg(r.value), 2) as avg_all, min(r.value) as min_all, max(r.value) as max_all, sum(r.value) as total_all,
             round(avg(r.value) filter (where r.day > current_date - 30), 2) as avg_30
      from r group by r.metric
    )
    select coalesce(jsonb_agg(to_jsonb(s)), '[]'::jsonb) into v_stats from s;
    insert into public.metric_stats_cache (user_id, stats, computed_at) values (v_uid, v_stats, clock_timestamp())
    on conflict (user_id) do update set stats = excluded.stats, computed_at = excluded.computed_at;
  end if;
  return query
    select x.metric, x.days, x.first_day, x.last_day, x.last_value, x.avg_all, x.min_all, x.max_all, x.total_all, x.avg_30
    from jsonb_to_recordset(v_stats) as x(metric text, days bigint, first_day date, last_day date, last_value numeric,
                                          avg_all numeric, min_all numeric, max_all numeric, total_all numeric, avg_30 numeric);
end $$;
revoke all on function public.metric_stats() from public, anon;
grant execute on function public.metric_stats() to authenticated;

-- ---------- 2. Alles in één verzoek ----------
create or replace function public.app_state(p_from date, p_to date, p_stats boolean default true)
returns jsonb language plpgsql set search_path = '' as $$
declare v_uid uuid := auth.uid();
begin
  if v_uid is null then raise exception 'Niet ingelogd'; end if;
  if p_to < p_from or p_to - p_from > 400 then raise exception 'Ongeldige periode'; end if;
  return jsonb_build_object(
    'metrics', (select coalesce(jsonb_agg(jsonb_build_object('id', m.id, 'name_nl', m.name_nl, 'unit', m.unit,
                  'lower_is_better', m.lower_is_better, 'aggregation', m.aggregation) order by m.id), '[]') from public.metrics m),
    'prefs', (select coalesce(jsonb_agg(to_jsonb(mp) - 'user_id' - 'updated_at'), '[]') from public.metric_prefs mp where mp.user_id = v_uid),
    'profile', (select jsonb_build_object('email', p.email, 'display_name', p.display_name, 'birth_date', p.birth_date, 'sex', p.sex,
                  'height_cm', p.height_cm, 'weight_kg', p.weight_kg, 'theme', p.theme, 'consent_health_at', p.consent_health_at,
                  'onboarded_at', p.onboarded_at, 'layout', p.layout) from public.profiles p where p.id = v_uid),
    'connections', (select coalesce(jsonb_agg(jsonb_build_object('provider', c.provider, 'status', c.status,
                  'last_sync_at', c.last_sync_at, 'last_error', c.last_error)), '[]') from public.connections c where c.user_id = v_uid),
    'daily', (select coalesce(jsonb_agg(to_jsonb(d)), '[]') from public.resolved_daily(p_from, p_to) d),
    'stats', case when p_stats then (select coalesce(jsonb_agg(to_jsonb(s)), '[]') from public.metric_stats() s) end
  );
end $$;
revoke all on function public.app_state(date, date, boolean) from public, anon;
grant execute on function public.app_state(date, date, boolean) to authenticated;

-- ---------- 3. Logboek alleen voor fouten ----------
create or replace function public.ingest_health_connect(p_rows jsonb)
returns integer language plpgsql security definer set search_path = '' as $$
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
  -- Alleen echt gewijzigde waarden schrijven: dezelfde upload elk kwartier maakt geen nieuwe rijversies
  do update set value = excluded.value, updated_at = now()
  where public.daily_metrics.value is distinct from excluded.value;
  get diagnostics v_count = row_count;

  insert into public.connections (user_id, provider, status, last_data_at, last_sync_at, last_error)
  values (v_uid, 'health_connect', 'active', now(), now(), null)
  on conflict (user_id, provider)
  do update set status = 'active', last_sync_at = now(), last_error = null,
                last_data_at = case when v_count > 0 then now() else public.connections.last_data_at end;
  return v_count;
end $$;

-- ---------- 4. Servertaak Google Health in porties ----------
create index if not exists connections_due on public.connections (provider, status, last_sync_at nulls first);
drop index if exists public.sync_runs_provider;          -- nooit gebruikt
drop index if exists public.daily_metrics_user_provider;  -- ontkoppelen kan via de primaire sleutel (user_id voorop)

create or replace function public.google_health_due(p_shard int, p_shards int, p_limit int, p_stale interval)
returns setof uuid language sql stable security definer set search_path = '' as $$
  select c.user_id from public.connections c
  where c.provider = 'google_health' and c.status = 'active'
    and (c.last_sync_at is null or c.last_sync_at < now() - p_stale)
    and (p_shards <= 1 or abs(hashtext(c.user_id::text)) % p_shards = p_shard)
  order by c.last_sync_at nulls first
  limit least(p_limit, 500)
$$;
revoke all on function public.google_health_due(int, int, int, interval) from public, anon, authenticated;

insert into public.app_secrets (name, value) values ('google_sync_shards', '1') on conflict (name) do nothing;

select cron.unschedule('google-health-sync') where exists (select 1 from cron.job where jobname = 'google-health-sync');
-- Elk kwartier: per portie één aanroep; de functie pakt alleen gebruikers die 3 uur niet zijn bijgewerkt
-- (de telefoon doet het meestal zelf al). Meer gebruikers: verhoog google_sync_shards in app_secrets.
select cron.schedule('google-health-sync', '*/15 * * * *', $cron$
  select net.http_post(
    url := 'https://qtythirnpokfptchavgy.supabase.co/functions/v1/google-health-sync',
    headers := jsonb_build_object('Content-Type', 'application/json',
      'x-cron-secret', (select value from public.app_secrets where name = 'cron_secret')),
    body := jsonb_build_object('shard', s, 'shards', n.shards),
    timeout_milliseconds := 120000)
  from (select greatest(1, (select value::int from public.app_secrets where name = 'google_sync_shards')) as shards) n,
       generate_series(0, n.shards - 1) s
$cron$);

select cron.unschedule('vitaal-cleanup') where exists (select 1 from cron.job where jobname = 'vitaal-cleanup');
select cron.schedule('vitaal-cleanup', '43 3 * * *', $cron$
  delete from public.oauth_states where created_at < now() - interval '1 hour';
  delete from public.provider_raw where received_at < now() - interval '30 days';
  delete from public.sync_runs where started_at < now() - interval '14 days';
  delete from public.metric_stats_cache where computed_at < now() - interval '30 days';
$cron$);

-- Oude logregels van geslaagde app-synchronisaties zijn niet meer nodig
delete from public.sync_runs where status = 'ok';

-- Export: geen sync_runs-logboek meer per succes, wel de fouten
create or replace function public.export_my_data() returns jsonb
language sql stable set search_path = '' as $$
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
    'sync_errors', coalesce((select jsonb_agg(to_jsonb(s) - 'user_id' order by s.started_at desc)
                             from public.sync_runs s where s.user_id = (select auth.uid())), '[]'::jsonb)
  )
$$;
