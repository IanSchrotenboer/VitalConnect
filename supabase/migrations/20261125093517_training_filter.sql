-- Welke trainingen tellen mee (Profiel → Koppelingen → Trainingen).
-- Alles blijft opgeslagen; het filter zit in training() en workouts_before(), zodat omschakelen direct werkt.
--
-- training_source:
--   auto            Google Health van je horloge als Google Health gekoppeld is, anders Health Connect (standaard)
--   google_health   alleen trainingen die Google Health van je horloge heeft (niet wat Google Health uit Health Connect overneemt)
--   health_connect  alleen trainingen uit Health Connect
--   all             alles, met ontdubbelen
-- training_own_only: trainingen die automatisch zijn herkend (vastlegmethode 'passive') tellen niet mee.
-- Wat je zelf logt of importeert, telt altijd mee.

alter table public.workouts add column if not exists recording text
  check (recording in ('active', 'passive', 'manual', 'derived', 'unknown'));

alter table public.profiles add column if not exists training_source text not null default 'auto'
  check (training_source in ('auto', 'google_health', 'health_connect', 'all'));
alter table public.profiles add column if not exists training_own_only boolean not null default false;
grant update (training_source, training_own_only) on public.profiles to authenticated;

-- Telt deze training mee bij deze instelling? (p_mode is al opgelost: nooit 'auto')
create or replace function public.workout_counts(p_source text, p_origin text, p_recording text, p_mode text, p_own boolean)
returns boolean
language sql
immutable
set search_path = ''
as $$
  select case
    when p_source in ('manual', 'file') then true
    when p_own and p_recording = 'passive' then false
    when p_mode = 'all' then true
    when p_mode = 'google_health' then p_source = 'google_health' and p_origin is distinct from 'HEALTH_CONNECT'
    when p_mode = 'health_connect' then p_source = 'health_connect'
    else false
  end
$$;
revoke all on function public.workout_counts(text, text, text, text, boolean) from public, anon;
grant execute on function public.workout_counts(text, text, text, text, boolean) to authenticated;

-- De instelling van de ingelogde gebruiker, met 'auto' opgelost
create or replace function public.training_mode(p_user uuid)
returns table (mode text, own_only boolean, chosen text)
language sql
stable
set search_path = ''
as $$
  select case p.training_source
           when 'auto' then case when exists (select 1 from public.connections c
                                              where c.user_id = p_user and c.provider = 'google_health' and c.status = 'active')
                                 then 'google_health' else 'health_connect' end
           else p.training_source end,
         p.training_own_only, p.training_source
  from public.profiles p where p.id = p_user
$$;
revoke all on function public.training_mode(uuid) from public, anon;
grant execute on function public.training_mode(uuid) to authenticated;

-- Ontdubbelen kijkt alleen naar trainingen die meetellen: een verborgen training drukt geen zichtbare weg
drop function if exists public.workout_hidden(uuid, uuid, text, timestamptz, integer);
create or replace function public.workout_hidden(p_user uuid, p_id uuid, p_source text, p_start timestamptz, p_dur integer,
                                                 p_mode text, p_own boolean)
returns boolean
language sql
stable
set search_path = ''
as $$
  select exists (
    select 1 from public.workouts o
    where o.user_id = p_user and o.id <> p_id
      and o.day between (p_start - interval '1 day')::date and (p_start + interval '1 day')::date
      and abs(extract(epoch from o.started_at - p_start)) <= 300
      and abs(o.duration_s - p_dur) <= greatest(180, 0.25 * p_dur)
      and public.workout_counts(o.source, o.origin, o.recording, p_mode, p_own)
      and (public.workout_prio(o.source) < public.workout_prio(p_source)
           or (public.workout_prio(o.source) = public.workout_prio(p_source) and o.id < p_id)))
$$;
revoke all on function public.workout_hidden(uuid, uuid, text, timestamptz, integer, text, boolean) from public, anon;
grant execute on function public.workout_hidden(uuid, uuid, text, timestamptz, integer, text, boolean) to authenticated;

create or replace function public.training(p_today date)
returns jsonb
language plpgsql
stable
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
  v_birth date;
  v_sex text;
  v_max numeric;
  v_rest numeric;
  v_mode text;
  v_own boolean;
  v_chosen text;
  v_filtered integer;
  atl numeric := 0;
  ctl numeric := 0;
  l numeric;
  first_day date;
  list jsonb;
  week jsonb;
  weeks jsonb;
  loads numeric[];
  monday date := date_trunc('week', p_today::timestamp)::date;
begin
  if v_uid is null then raise exception 'Niet ingelogd'; end if;
  select p.birth_date, p.sex into v_birth, v_sex from public.profiles p where p.id = v_uid;
  select m.mode, m.own_only, m.chosen into v_mode, v_own, v_chosen from public.training_mode(v_uid) m;
  v_mode := coalesce(v_mode, 'health_connect'); v_own := coalesce(v_own, false);
  if v_birth is not null then v_max := round(208 - 0.7 * extract(year from age(p_today, v_birth))); end if;
  select avg(value) into v_rest from public.resolved_series('rhr', p_today - 29, p_today) where value > 0;

  -- Hoeveel trainingen uit een bron de instelling de laatste 90 dagen verbergt (voor de uitleg in de app)
  select count(*) into v_filtered from public.workouts w
  where w.user_id = v_uid and w.day between p_today - 89 and p_today
    and not public.workout_counts(w.source, w.origin, w.recording, v_mode, v_own);

  with tw as (
    select w.*, public.trimp(w.duration_s / 60.0, w.avg_hr, v_rest, v_max, v_sex) as load
    from public.workouts w where w.user_id = v_uid and w.day between p_today - 179 and p_today
      and public.workout_counts(w.source, w.origin, w.recording, v_mode, v_own)
      and not public.workout_hidden(w.user_id, w.id, w.source, w.started_at, w.duration_s, v_mode, v_own)
  )
  select
    (select coalesce(jsonb_agg(jsonb_build_object('id', x.id, 'source', x.source, 'external_id', x.external_id, 'sport', x.sport,
              'title', x.title, 'day', x.day, 'started_at', x.started_at, 'duration_s', x.duration_s, 'distance_m', x.distance_m,
              'kcal', x.kcal, 'avg_hr', x.avg_hr, 'max_hr', x.max_hr, 'ascent_m', x.ascent_m, 'notes', x.notes, 'origin', x.origin,
              'recording', x.recording, 'load', x.load) order by x.day desc, x.started_at desc), '[]')
       from (select * from tw where tw.day > p_today - 90 order by tw.day desc, tw.started_at desc limit 300) x),
    (select jsonb_build_object('count', count(*), 'minutes', round(coalesce(sum(tw.duration_s), 0) / 60.0),
              'distance_km', round(coalesce(sum(tw.distance_m), 0) / 1000.0, 1), 'kcal', round(coalesce(sum(tw.kcal), 0)),
              'load', round(sum(tw.load)))
       from tw where tw.day > p_today - 7),
    (select coalesce(jsonb_agg(jsonb_build_object('week', g.wk, 'count', s.n, 'minutes', s.mins, 'load', s.lo) order by g.wk), '[]')
       from (select gs::date wk from generate_series(monday - 49, monday, interval '7 days') gs) g
       cross join lateral (select count(*) n, round(coalesce(sum(tw.duration_s), 0) / 60.0) mins, round(sum(tw.load)) lo
                           from tw where tw.day >= g.wk and tw.day < g.wk + 7) s),
    (select array_agg(coalesce(s.l, 0) order by g.d)
       from (select gs::date d from generate_series(p_today - 179, p_today, interval '1 day') gs) g
       left join (select tw.day, sum(tw.load) l from tw group by tw.day) s on s.day = g.d),
    (select min(tw.day) from tw where tw.load is not null)
  into list, week, weeks, loads, first_day;

  -- Acuut (7 dagen) en chronisch (42 dagen): exponentieel gewogen gemiddelde van de dagbelasting (Banister)
  if first_day is not null then
    foreach l in array loads loop
      atl := atl + (l - atl) * (1 - exp(-1.0 / 7));
      ctl := ctl + (l - ctl) * (1 - exp(-1.0 / 42));
    end loop;
  end if;

  return jsonb_build_object(
    'workouts', list, 'week', week, 'weeks', weeks,
    'filter', jsonb_build_object('source', coalesce(v_chosen, 'auto'), 'mode', v_mode, 'own_only', v_own, 'hidden', v_filtered),
    'load', jsonb_build_object(
      'ready', first_day is not null, 'days', case when first_day is not null then p_today - first_day + 1 end,
      'acute', case when first_day is not null then round(atl, 1) end,
      'chronic', case when first_day is not null then round(ctl, 1) end,
      'form', case when first_day is not null then round(ctl - atl, 1) end,
      'hrmax', v_max, 'rest', round(v_rest, 1), 'sex', v_sex));
end $$;
revoke all on function public.training(date) from public, anon;
grant execute on function public.training(date) to authenticated;

create or replace function public.workouts_before(p_day date, p_limit integer default 50)
returns jsonb
language sql
stable
set search_path = ''
as $$
  select coalesce(jsonb_agg(to_jsonb(w) - 'user_id' - 'created_at' - 'updated_at' order by w.day desc, w.started_at desc), '[]')
  from public.training_mode((select auth.uid())) m
  cross join lateral (
    select * from public.workouts w where w.user_id = (select auth.uid()) and w.day < p_day
      and public.workout_counts(w.source, w.origin, w.recording, m.mode, m.own_only)
      and not public.workout_hidden(w.user_id, w.id, w.source, w.started_at, w.duration_s, m.mode, m.own_only)
    order by w.day desc, w.started_at desc limit least(greatest(coalesce(p_limit, 50), 1), 200)) w
$$;
revoke all on function public.workouts_before(date, integer) from public, anon;
grant execute on function public.workouts_before(date, integer) to authenticated;

-- save_workouts neemt ook de vastlegmethode over
create or replace function public.save_workouts(p_rows jsonb)
returns integer
language plpgsql
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
  v_count integer;
begin
  if v_uid is null then raise exception 'Niet ingelogd'; end if;
  if jsonb_typeof(p_rows) <> 'array' or jsonb_array_length(p_rows) > 2000 then raise exception 'Ongeldige payload'; end if;

  with r as (
    select e->>'source' as source, e->>'external_id' as external_id, e->>'sport' as sport,
           nullif(left(btrim(e->>'title'), 80), '') as title, public.try_date(e->>'day') as day,
           public.try_time(e->>'started_at') as started_at, round(public.try_num(e->'duration_s')) as duration_s,
           round(public.try_num(e->'distance_m'), 1) as distance_m, round(public.try_num(e->'kcal')) as kcal,
           round(public.try_num(e->'avg_hr')) as avg_hr, round(public.try_num(e->'max_hr')) as max_hr,
           round(public.try_num(e->'ascent_m')) as ascent_m, nullif(left(btrim(e->>'notes'), 1000), '') as notes,
           left(e->>'origin', 120) as origin,
           case when e->>'recording' in ('active', 'passive', 'manual', 'derived', 'unknown') then e->>'recording' end as recording
    from jsonb_array_elements(p_rows) e
    where jsonb_typeof(e) = 'object'
  ), ok as (
    select distinct on (source, external_id) r.*
    from r
    where source in ('health_connect', 'google_health', 'manual', 'file')
      and external_id ~ '^[A-Za-z0-9_.:@/+=-]{1,200}$'
      and sport in ('running', 'walking', 'hiking', 'cycling', 'swimming', 'strength', 'hiit', 'yoga',
        'rowing', 'elliptical', 'team', 'racket', 'dance', 'martial', 'winter', 'other')
      and day between date '1990-01-01' and current_date + 1
      and started_at between timestamptz '1990-01-01' and now() + interval '1 day'
      and duration_s between 60 and 86400
      and (distance_m is null or distance_m between 0 and 1000000)
      and (kcal is null or kcal between 0 and 20000)
      and (avg_hr is null or avg_hr between 25 and 250)
      and (max_hr is null or max_hr between 25 and 250)
      and (ascent_m is null or ascent_m between 0 and 20000)
  )
  insert into public.workouts as w (user_id, source, external_id, sport, title, day, started_at, duration_s, distance_m, kcal,
                                    avg_hr, max_hr, ascent_m, notes, origin, recording)
  select v_uid, source, external_id, sport, title, day, started_at, duration_s, distance_m, kcal,
         avg_hr, max_hr, ascent_m, notes, origin, recording
  from ok
  on conflict (user_id, source, external_id) do update set
    sport = excluded.sport, title = excluded.title, day = excluded.day, started_at = excluded.started_at,
    duration_s = excluded.duration_s, distance_m = excluded.distance_m, kcal = excluded.kcal, avg_hr = excluded.avg_hr,
    max_hr = excluded.max_hr, ascent_m = excluded.ascent_m, notes = excluded.notes, origin = excluded.origin,
    recording = excluded.recording, updated_at = now()
  where (w.sport, w.title, w.day, w.started_at, w.duration_s, w.distance_m, w.kcal, w.avg_hr, w.max_hr, w.ascent_m, w.notes, w.origin, w.recording)
    is distinct from (excluded.sport, excluded.title, excluded.day, excluded.started_at, excluded.duration_s, excluded.distance_m,
                      excluded.kcal, excluded.avg_hr, excluded.max_hr, excluded.ascent_m, excluded.notes, excluded.origin, excluded.recording);
  get diagnostics v_count = row_count;
  return v_count;
end $$;
revoke all on function public.save_workouts(jsonb) from public, anon;
grant execute on function public.save_workouts(jsonb) to authenticated;

-- app_state: het profiel geeft ook de trainingsinstelling mee
create or replace function public.app_state(p_from date, p_to date, p_stats boolean default true)
returns jsonb
language plpgsql
set search_path = ''
as $function$
declare v_uid uuid := auth.uid();
begin
  if v_uid is null then raise exception 'Niet ingelogd'; end if;
  if p_to < p_from or p_to - p_from > 400 then raise exception 'Ongeldige periode'; end if;
  return jsonb_build_object(
    'metrics', (select coalesce(jsonb_agg(jsonb_build_object('id', m.id, 'name_nl', m.name_nl, 'unit', m.unit,
                  'lower_is_better', m.lower_is_better, 'aggregation', m.aggregation) order by m.id), '[]') from public.metrics m),
    'providers', (select coalesce(jsonb_agg(jsonb_build_object('id', pr.id, 'name', pr.name, 'sync', pr.sync,
                  'available', pr.available) order by pr.priority), '[]') from public.providers pr),
    'prefs', (select coalesce(jsonb_agg(to_jsonb(mp) - 'user_id' - 'updated_at'), '[]') from public.metric_prefs mp where mp.user_id = v_uid),
    'profile', (select jsonb_build_object('email', p.email, 'display_name', p.display_name, 'birth_date', p.birth_date, 'sex', p.sex,
                  'height_cm', p.height_cm, 'weight_kg', p.weight_kg, 'theme', p.theme, 'consent_health_at', p.consent_health_at,
                  'onboarded_at', p.onboarded_at, 'layout', p.layout,
                  'training_source', p.training_source, 'training_own_only', p.training_own_only) from public.profiles p where p.id = v_uid),
    'connections', (select coalesce(jsonb_agg(jsonb_build_object('provider', c.provider, 'status', c.status,
                  'last_sync_at', c.last_sync_at, 'last_error', c.last_error)), '[]') from public.connections c where c.user_id = v_uid),
    'daily', (select coalesce(jsonb_agg(to_jsonb(d)), '[]') from public.resolved_daily(p_from, p_to) d),
    'stats', case when p_stats then (select coalesce(jsonb_agg(to_jsonb(s)), '[]') from public.metric_stats() s) end,
    'fitness', public.fitness(p_to),
    'training', public.training(p_to)
  );
end $function$;
