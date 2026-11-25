-- Trainingen (tabblad Training): uit Health Connect, Google Health, zelf gelogd of uit een FIT- of JSON-bestand.
-- Trainingsbelasting volgens Banister (TRIMP uit gemiddelde hartslag) met acute en chronische belasting (7 en 42 dagen).
-- Zie docs/berekeningen.md.

create table public.workouts (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  source text not null check (source in ('health_connect', 'google_health', 'manual', 'file')),
  external_id text not null check (char_length(external_id) between 1 and 200),
  sport text not null check (sport in ('running', 'walking', 'hiking', 'cycling', 'swimming', 'strength', 'hiit', 'yoga',
    'rowing', 'elliptical', 'team', 'racket', 'dance', 'martial', 'winter', 'other')),
  title text check (char_length(title) <= 80),
  day date not null,
  started_at timestamptz not null,
  duration_s integer not null check (duration_s between 60 and 86400),
  distance_m numeric check (distance_m between 0 and 1000000),
  kcal numeric check (kcal between 0 and 20000),
  avg_hr smallint check (avg_hr between 25 and 250),
  max_hr smallint check (max_hr between 25 and 250),
  ascent_m numeric check (ascent_m between 0 and 20000),
  notes text check (char_length(notes) <= 1000),
  origin text check (char_length(origin) <= 120),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (user_id, source, external_id)
);
create index workouts_user_day on public.workouts (user_id, day desc, started_at desc);

alter table public.workouts enable row level security;
create policy workouts_select on public.workouts for select to authenticated using (user_id = (select auth.uid()));
create policy workouts_insert on public.workouts for insert to authenticated with check (user_id = (select auth.uid()));
create policy workouts_update on public.workouts for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
create policy workouts_delete on public.workouts for delete to authenticated using (user_id = (select auth.uid()));
revoke all on public.workouts from anon, public;
grant select, insert, update, delete on public.workouts to authenticated;

-- Tekst naar getal zonder fout; null bij onzin, NaN of oneindig
create or replace function public.try_num(p jsonb)
returns numeric
language plpgsql
immutable
set search_path = ''
as $$
declare v numeric;
begin
  if p is null or jsonb_typeof(p) <> 'number' then return null; end if;
  v := (p #>> '{}')::numeric;
  return v;
exception when others then return null;
end $$;
revoke all on function public.try_num(jsonb) from public, anon, authenticated;
grant execute on function public.try_num(jsonb) to authenticated;

create or replace function public.try_time(p text)
returns timestamptz
language plpgsql
immutable
set search_path = ''
as $$
begin
  if p is null or p !~ '^\d{4}-\d{2}-\d{2}T' then return null; end if;
  return p::timestamptz;
exception when others then return null;
end $$;
revoke all on function public.try_time(text) from public, anon, authenticated;
grant execute on function public.try_time(text) to authenticated;
grant execute on function public.try_date(text) to authenticated;

-- Opslaan (nieuw of bijwerken). Onzin per regel wordt overgeslagen, niet de hele upload.
-- Alleen gewijzigde trainingen worden geschreven. Geeft het aantal opgeslagen trainingen terug.
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
           left(e->>'origin', 120) as origin
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
                                    avg_hr, max_hr, ascent_m, notes, origin)
  select v_uid, source, external_id, sport, title, day, started_at, duration_s, distance_m, kcal,
         avg_hr, max_hr, ascent_m, notes, origin
  from ok
  on conflict (user_id, source, external_id) do update set
    sport = excluded.sport, title = excluded.title, day = excluded.day, started_at = excluded.started_at,
    duration_s = excluded.duration_s, distance_m = excluded.distance_m, kcal = excluded.kcal, avg_hr = excluded.avg_hr,
    max_hr = excluded.max_hr, ascent_m = excluded.ascent_m, notes = excluded.notes, origin = excluded.origin, updated_at = now()
  where (w.sport, w.title, w.day, w.started_at, w.duration_s, w.distance_m, w.kcal, w.avg_hr, w.max_hr, w.ascent_m, w.notes, w.origin)
    is distinct from (excluded.sport, excluded.title, excluded.day, excluded.started_at, excluded.duration_s, excluded.distance_m,
                      excluded.kcal, excluded.avg_hr, excluded.max_hr, excluded.ascent_m, excluded.notes, excluded.origin);
  get diagnostics v_count = row_count;
  return v_count;
end $$;
revoke all on function public.save_workouts(jsonb) from public, anon;
grant execute on function public.save_workouts(jsonb) to authenticated;

-- Verwijderen: op id (zelf gelogd of uit een bestand) of op externe id (verwijderd in de bron, bijv. Health Connect)
create or replace function public.delete_workouts(p_ids uuid[] default null, p_source text default null, p_external text[] default null)
returns integer
language plpgsql
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
  v_count integer := 0;
  n integer;
begin
  if v_uid is null then raise exception 'Niet ingelogd'; end if;
  if coalesce(cardinality(p_ids), 0) + coalesce(cardinality(p_external), 0) > 2000 then raise exception 'Te veel'; end if;
  if p_ids is not null then
    delete from public.workouts where user_id = v_uid and id = any (p_ids) and source in ('manual', 'file');
    get diagnostics n = row_count; v_count := v_count + n;
  end if;
  if p_source is not null and p_external is not null then
    delete from public.workouts where user_id = v_uid and source = p_source and external_id = any (p_external);
    get diagnostics n = row_count; v_count := v_count + n;
  end if;
  return v_count;
end $$;
revoke all on function public.delete_workouts(uuid[], text, text[]) from public, anon;
grant execute on function public.delete_workouts(uuid[], text, text[]) to authenticated;

-- Trainingsbelasting volgens Banister (TRIMP): minuten × x × k × e^(b·x), x = (gem. hartslag − rust) ÷ (max − rust).
-- Man k 0,64 b 1,92; vrouw k 0,86 b 1,67; niet ingevuld: het gemiddelde van beide.
create or replace function public.trimp(p_minutes numeric, p_avg numeric, p_rest numeric, p_max numeric, p_sex text)
returns numeric
language sql
immutable
set search_path = ''
as $$
  select case when p_avg is null or p_rest is null or p_max is null or p_max - p_rest < 20 then null else
    round(case p_sex
      when 'male' then p_minutes * x * 0.64 * exp(1.92 * x)
      when 'female' then p_minutes * x * 0.86 * exp(1.67 * x)
      else p_minutes * x * (0.64 * exp(1.92 * x) + 0.86 * exp(1.67 * x)) / 2 end, 1) end
  from (select least(1, greatest(0, (p_avg - p_rest) / nullif(p_max - p_rest, 0))) x) s
$$;
revoke all on function public.trimp(numeric, numeric, numeric, numeric, text) from public, anon;
grant execute on function public.trimp(numeric, numeric, numeric, numeric, text) to authenticated;

-- Training voor app_state: trainingen van 90 dagen, deze week, 8 weken en belasting (acuut 7, chronisch 42 dagen)
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
  if v_birth is not null then v_max := round(208 - 0.7 * extract(year from age(p_today, v_birth))); end if;
  select avg(value) into v_rest from public.resolved_series('rhr', p_today - 29, p_today) where value > 0;

  with tw as (
    select w.*, public.trimp(w.duration_s / 60.0, w.avg_hr, v_rest, v_max, v_sex) as load
    from public.workouts w where w.user_id = v_uid and w.day between p_today - 179 and p_today
  )
  select
    (select coalesce(jsonb_agg(jsonb_build_object('id', x.id, 'source', x.source, 'external_id', x.external_id, 'sport', x.sport,
              'title', x.title, 'day', x.day, 'started_at', x.started_at, 'duration_s', x.duration_s, 'distance_m', x.distance_m,
              'kcal', x.kcal, 'avg_hr', x.avg_hr, 'max_hr', x.max_hr, 'ascent_m', x.ascent_m, 'notes', x.notes, 'origin', x.origin,
              'load', x.load) order by x.day desc, x.started_at desc), '[]')
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
    'load', jsonb_build_object(
      'ready', first_day is not null, 'days', case when first_day is not null then p_today - first_day + 1 end,
      'acute', case when first_day is not null then round(atl, 1) end,
      'chronic', case when first_day is not null then round(ctl, 1) end,
      'form', case when first_day is not null then round(ctl - atl, 1) end,
      'hrmax', v_max, 'rest', round(v_rest, 1), 'sex', v_sex));
end $$;
revoke all on function public.training(date) from public, anon;
grant execute on function public.training(date) to authenticated;

-- Oudere trainingen, bladzijde voor bladzijde (tabblad Training, "Oudere trainingen")
create or replace function public.workouts_before(p_day date, p_limit integer default 50)
returns jsonb
language sql
stable
set search_path = ''
as $$
  select coalesce(jsonb_agg(to_jsonb(w) - 'user_id' - 'created_at' - 'updated_at' order by w.day desc, w.started_at desc), '[]')
  from (select * from public.workouts w where w.user_id = (select auth.uid()) and w.day < p_day
        order by w.day desc, w.started_at desc limit least(greatest(coalesce(p_limit, 50), 1), 200)) w
$$;
revoke all on function public.workouts_before(date, integer) from public, anon;
grant execute on function public.workouts_before(date, integer) to authenticated;

-- Export bevat ook de trainingen
create or replace function public.export_my_data()
returns jsonb
language sql
stable
set search_path = ''
as $function$
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
    'workouts', coalesce((select jsonb_agg(to_jsonb(w) - 'user_id' order by w.day, w.started_at)
                          from public.workouts w where w.user_id = (select auth.uid())), '[]'::jsonb),
    'sync_errors', coalesce((select jsonb_agg(to_jsonb(s) - 'user_id' order by s.started_at desc)
                             from public.sync_runs s where s.user_id = (select auth.uid())), '[]'::jsonb)
  )
$function$;

-- app_state geeft ook de training mee (nog steeds één verzoek bij openen)
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
                  'onboarded_at', p.onboarded_at, 'layout', p.layout) from public.profiles p where p.id = v_uid),
    'connections', (select coalesce(jsonb_agg(jsonb_build_object('provider', c.provider, 'status', c.status,
                  'last_sync_at', c.last_sync_at, 'last_error', c.last_error)), '[]') from public.connections c where c.user_id = v_uid),
    'daily', (select coalesce(jsonb_agg(to_jsonb(d)), '[]') from public.resolved_daily(p_from, p_to) d),
    'stats', case when p_stats then (select coalesce(jsonb_agg(to_jsonb(s)), '[]') from public.metric_stats() s) end,
    'fitness', public.fitness(p_to),
    'training', public.training(p_to)
  );
end $function$;
