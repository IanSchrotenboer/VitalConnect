-- Wandelingen filteren op wat de wandeling zelf laat zien, in plaats van op de vastlegmethode van de bron.
-- Google Health en de Fitbit-app markeren ook zelf gestarte trainingen als 'automatisch herkend'
-- (PASSIVELY_MEASURED), dus training_own_only verborg echte trainingen. Die keuze vervalt.
--
-- training_walk_min:   wandelingen korter dan dit (minuten) tellen niet mee; 0 = uit (10, 20 of 30)
-- training_walk_light: lichte wandelingen tellen niet mee. Licht = gemiddelde hartslag onder
--                      rust + 30% van de hartslagreserve (Karvonen; rust = gemiddelde van 30 dagen,
--                      max = 208 − 0,7 × leeftijd). Zonder hartslag of grens: sneller dan 4 km/u telt mee.
--                      Zonder hartslag én afstand telt de wandeling mee (niet te beoordelen).
-- Alleen sport 'walking'; wat je zelf logt of importeert telt altijd mee.

alter table public.profiles add column if not exists training_walk_min integer not null default 0
  check (training_walk_min in (0, 10, 20, 30));
alter table public.profiles add column if not exists training_walk_light boolean not null default false;
grant update (training_walk_min, training_walk_light) on public.profiles to authenticated;

drop function if exists public.workout_hidden(uuid, uuid, text, timestamptz, integer, text, boolean);
drop function if exists public.workout_counts(text, text, text, text, boolean);
drop function if exists public.training_mode(uuid);
alter table public.profiles drop column if exists training_own_only;

-- Telt deze training mee bij deze instelling? (p_mode is al opgelost: nooit 'auto')
create or replace function public.workout_counts(p_source text, p_origin text, p_sport text, p_dur integer, p_dist numeric,
                                                 p_hr numeric, p_mode text, p_walk_min integer, p_walk_light boolean, p_hr_limit numeric)
returns boolean
language sql
immutable
set search_path = ''
as $$
  select case
    when p_source in ('manual', 'file') then true
    when not case p_mode
               when 'all' then true
               when 'google_health' then p_source = 'google_health' and p_origin is distinct from 'HEALTH_CONNECT'
               when 'health_connect' then p_source = 'health_connect'
               else false end then false
    when p_sport <> 'walking' then true
    when coalesce(p_walk_min, 0) > 0 and p_dur < p_walk_min * 60 then false
    when not coalesce(p_walk_light, false) then true
    when p_hr is not null and p_hr_limit is not null then p_hr >= p_hr_limit
    when p_dist is not null and p_dur > 0 then p_dist / p_dur * 3.6 >= 4
    else true
  end
$$;
revoke all on function public.workout_counts(text, text, text, integer, numeric, numeric, text, integer, boolean, numeric) from public, anon;
grant execute on function public.workout_counts(text, text, text, integer, numeric, numeric, text, integer, boolean, numeric) to authenticated;

-- De instelling van de ingelogde gebruiker, met 'auto' opgelost en de hartslaggrens voor lichte wandelingen
create or replace function public.training_mode(p_user uuid, p_today date)
returns table (mode text, chosen text, walk_min integer, walk_light boolean, hr_rest numeric, hr_max numeric, hr_limit numeric)
language sql
stable
set search_path = ''
as $$
  with p as (select * from public.profiles where id = p_user),
  hr as (
    select (select avg(value) from public.resolved_series('rhr', p_today - 29, p_today) where value > 0) as rest,
           (select round(208 - 0.7 * extract(year from age(p_today, p.birth_date))) from p where p.birth_date is not null) as mx
  )
  select case p.training_source
           when 'auto' then case when exists (select 1 from public.connections c
                                              where c.user_id = p_user and c.provider = 'google_health' and c.status = 'active')
                                 then 'google_health' else 'health_connect' end
           else p.training_source end,
         p.training_source, p.training_walk_min, p.training_walk_light, hr.rest, hr.mx,
         case when hr.rest is not null and hr.mx is not null and hr.mx - hr.rest >= 20 then round(hr.rest + 0.3 * (hr.mx - hr.rest)) end
  from p cross join hr
$$;
revoke all on function public.training_mode(uuid, date) from public, anon;
grant execute on function public.training_mode(uuid, date) to authenticated;

-- Ontdubbelen kijkt alleen naar trainingen die meetellen: een verborgen training drukt geen zichtbare weg
create or replace function public.workout_hidden(p_user uuid, p_id uuid, p_source text, p_start timestamptz, p_dur integer,
                                                 p_mode text, p_walk_min integer, p_walk_light boolean, p_hr_limit numeric)
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
      and public.workout_counts(o.source, o.origin, o.sport, o.duration_s, o.distance_m, o.avg_hr, p_mode, p_walk_min, p_walk_light, p_hr_limit)
      and (public.workout_prio(o.source) < public.workout_prio(p_source)
           or (public.workout_prio(o.source) = public.workout_prio(p_source) and o.id < p_id)))
$$;
revoke all on function public.workout_hidden(uuid, uuid, text, timestamptz, integer, text, integer, boolean, numeric) from public, anon;
grant execute on function public.workout_hidden(uuid, uuid, text, timestamptz, integer, text, integer, boolean, numeric) to authenticated;

create or replace function public.training(p_today date)
returns jsonb
language plpgsql
stable
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
  v_sex text;
  v_max numeric;
  v_rest numeric;
  m record;
  v_hid_src integer;
  v_hid_walk integer;
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
  select p.sex into v_sex from public.profiles p where p.id = v_uid;
  select * into m from public.training_mode(v_uid, p_today);
  v_max := m.hr_max; v_rest := m.hr_rest;

  -- Hoeveel trainingen de instelling de laatste 90 dagen verbergt: om de bron, en wandelingen om duur of inspanning
  select count(*) filter (where not public.workout_counts(w.source, w.origin, 'other', w.duration_s, w.distance_m, w.avg_hr,
                                                          coalesce(m.mode, 'health_connect'), 0, false, null)),
         count(*) filter (where public.workout_counts(w.source, w.origin, 'other', w.duration_s, w.distance_m, w.avg_hr,
                                                      coalesce(m.mode, 'health_connect'), 0, false, null)
                            and not public.workout_counts(w.source, w.origin, w.sport, w.duration_s, w.distance_m, w.avg_hr,
                                                          coalesce(m.mode, 'health_connect'), m.walk_min, m.walk_light, m.hr_limit))
  into v_hid_src, v_hid_walk
  from public.workouts w where w.user_id = v_uid and w.day between p_today - 89 and p_today;

  with tw as (
    select w.*, public.trimp(w.duration_s / 60.0, w.avg_hr, v_rest, v_max, v_sex) as load
    from public.workouts w where w.user_id = v_uid and w.day between p_today - 179 and p_today
      and public.workout_counts(w.source, w.origin, w.sport, w.duration_s, w.distance_m, w.avg_hr,
                                coalesce(m.mode, 'health_connect'), m.walk_min, m.walk_light, m.hr_limit)
      and not public.workout_hidden(w.user_id, w.id, w.source, w.started_at, w.duration_s,
                                    coalesce(m.mode, 'health_connect'), m.walk_min, m.walk_light, m.hr_limit)
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
    'filter', jsonb_build_object('source', coalesce(m.chosen, 'auto'), 'mode', coalesce(m.mode, 'health_connect'),
      'walk_min', coalesce(m.walk_min, 0), 'walk_light', coalesce(m.walk_light, false), 'hr_limit', m.hr_limit,
      'hidden', v_hid_src + v_hid_walk, 'hidden_source', v_hid_src, 'hidden_walks', v_hid_walk),
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
  from public.training_mode((select auth.uid()), current_date) m
  cross join lateral (
    select * from public.workouts w where w.user_id = (select auth.uid()) and w.day < p_day
      and public.workout_counts(w.source, w.origin, w.sport, w.duration_s, w.distance_m, w.avg_hr, m.mode, m.walk_min, m.walk_light, m.hr_limit)
      and not public.workout_hidden(w.user_id, w.id, w.source, w.started_at, w.duration_s, m.mode, m.walk_min, m.walk_light, m.hr_limit)
    order by w.day desc, w.started_at desc limit least(greatest(coalesce(p_limit, 50), 1), 200)) w
$$;
revoke all on function public.workouts_before(date, integer) from public, anon;
grant execute on function public.workouts_before(date, integer) to authenticated;

-- app_state: het profiel geeft de nieuwe keuzes mee
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
                  'onboarded_at', p.onboarded_at, 'layout', p.layout, 'training_source', p.training_source,
                  'training_walk_min', p.training_walk_min, 'training_walk_light', p.training_walk_light)
                from public.profiles p where p.id = v_uid),
    'connections', (select coalesce(jsonb_agg(jsonb_build_object('provider', c.provider, 'status', c.status,
                  'last_sync_at', c.last_sync_at, 'last_error', c.last_error)), '[]') from public.connections c where c.user_id = v_uid),
    'daily', (select coalesce(jsonb_agg(to_jsonb(d)), '[]') from public.resolved_daily(p_from, p_to) d),
    'stats', case when p_stats then (select coalesce(jsonb_agg(to_jsonb(s)), '[]') from public.metric_stats() s) end,
    'fitness', public.fitness(p_to),
    'training', public.training(p_to)
  );
end $function$;
