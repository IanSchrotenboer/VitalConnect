-- Dezelfde training via twee wegen (bijv. Fitbit via Google Health én via Health Connect) telt maar één keer.
-- Dubbel = start binnen 5 minuten en duur binnen 25% (minstens 3 minuten). Voorrang: bestand, zelf gelogd, Google Health, Health Connect.
create or replace function public.workout_prio(p_source text)
returns integer
language sql
immutable
set search_path = ''
as $$ select case p_source when 'file' then 1 when 'manual' then 2 when 'google_health' then 3 else 4 end $$;
revoke all on function public.workout_prio(text) from public, anon;
grant execute on function public.workout_prio(text) to authenticated;

create or replace function public.workout_hidden(p_user uuid, p_id uuid, p_source text, p_start timestamptz, p_dur integer)
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
      and (public.workout_prio(o.source) < public.workout_prio(p_source)
           or (public.workout_prio(o.source) = public.workout_prio(p_source) and o.id < p_id)))
$$;
revoke all on function public.workout_hidden(uuid, uuid, text, timestamptz, integer) from public, anon;
grant execute on function public.workout_hidden(uuid, uuid, text, timestamptz, integer) to authenticated;

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
      and not public.workout_hidden(w.user_id, w.id, w.source, w.started_at, w.duration_s)
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
          and not public.workout_hidden(w.user_id, w.id, w.source, w.started_at, w.duration_s)
        order by w.day desc, w.started_at desc limit least(greatest(coalesce(p_limit, 50), 1), 200)) w
$$;
revoke all on function public.workouts_before(date, integer) from public, anon;
grant execute on function public.workouts_before(date, integer) to authenticated;

