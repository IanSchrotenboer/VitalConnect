-- Herstel en fitheid, berekend volgens gangbare methodes uit de sportwetenschap.
-- Komt mee in app_state (één verzoek bij openen). Zie docs/berekeningen.md.

-- Eén factor (HRV of rusthartslag): 7-daags gemiddelde tegen de eigen basislijn van de 60 dagen ervoor.
-- Normaal bereik = basislijn ± kleinste zinvolle verandering (0,5 × SD) (Plews e.a. 2012/2013; AIS-richtlijn HRV).
-- p_log: waarden zijn ln(RMSSD); de uitkomst staat weer in de meeteenheid (ms of bpm).
create or replace function public.fitness_band(
  p_v7 numeric, p_mu numeric, p_sd numeric, p_n7 bigint, p_nb bigint, p_log boolean, p_lower_better boolean)
returns jsonb
language plpgsql
immutable
set search_path = ''
as $$
declare
  swc numeric;
  z numeric;
  st text;
begin
  -- Minstens 3 metingen in 7 dagen (Plews e.a. 2014) en 14 dagen basislijn
  if coalesce(p_n7, 0) < 3 or coalesce(p_nb, 0) < 14 or coalesce(p_sd, 0) = 0 then
    return jsonb_build_object('ready', false, 'n7', coalesce(p_n7, 0), 'nb', coalesce(p_nb, 0),
      'value', case when p_n7 > 0 then round(case when p_log then exp(p_v7) else p_v7 end, 1) end);
  end if;
  swc := 0.5 * p_sd;
  z := (p_v7 - p_mu) / p_sd;
  st := case when p_v7 < p_mu - swc then 'laag' when p_v7 > p_mu + swc then 'hoog' else 'normaal' end;
  return jsonb_build_object(
    'ready', true, 'n7', p_n7, 'nb', p_nb, 'state', st,
    'bad', case when p_lower_better then st = 'hoog' else st = 'laag' end,
    'points', greatest(0, least(100, round(50 + (case when p_lower_better then -25 else 25 end) * z))),
    'value', round(case when p_log then exp(p_v7) else p_v7 end, 1),
    'low', round(case when p_log then exp(p_mu - swc) else p_mu - swc end, 1),
    'high', round(case when p_log then exp(p_mu + swc) else p_mu + swc end, 1),
    'min', round(case when p_log then exp(p_mu - 2.5 * p_sd) else p_mu - 2.5 * p_sd end, 1),
    'max', round(case when p_log then exp(p_mu + 2.5 * p_sd) else p_mu + 2.5 * p_sd end, 1));
end $$;

create or replace function public.fitness(p_today date)
returns jsonb
language plpgsql
stable
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
  hb record;
  rb record;
  sb record;
  v_need numeric;
  hrv jsonb;
  rhr jsonb;
  slp jsonb;
  trend jsonb;
  sum_w numeric := 0;
  sum_p numeric := 0;
  uit int := 0;
  s_bad boolean := false;
  v_score int;
  v_stand int;
  v_birth date;
  v_sex text;
  v_age int;
  v_hrmax int;
  vo2_src numeric;
  vo2_day date;
  vo2_est numeric;
  pf numeric;
begin
  if v_uid is null then raise exception 'Niet ingelogd'; end if;

  -- HRV als ln(RMSSD) en rusthartslag: vandaag + 6 dagen ervoor, tegen dag -66 t/m -7
  select avg(ln(value)) filter (where day > p_today - 7) v7, count(*) filter (where day > p_today - 7) n7,
         avg(ln(value)) filter (where day <= p_today - 7) mu, stddev_samp(ln(value)) filter (where day <= p_today - 7) sd,
         count(*) filter (where day <= p_today - 7) nb
    into hb from public.resolved_series('hrv', p_today - 66, p_today) where value > 0;
  select avg(value) filter (where day > p_today - 7) v7, count(*) filter (where day > p_today - 7) n7,
         avg(value) filter (where day <= p_today - 7) mu, stddev_samp(value) filter (where day <= p_today - 7) sd,
         count(*) filter (where day <= p_today - 7) nb
    into rb from public.resolved_series('rhr', p_today - 66, p_today) where value > 0;
  hrv := public.fitness_band(hb.v7, hb.mu, hb.sd, hb.n7, hb.nb, true, false);
  rhr := public.fitness_band(rb.v7, rb.mu, rb.sd, rb.n7, rb.nb, false, true);

  -- Slaap: afgelopen nacht en 7 nachten tegen het slaapdoel (standaard 8 uur)
  select avg(value) wk, count(*) n, max(value) filter (where day = p_today) last
    into sb from public.resolved_series('sleep', p_today - 6, p_today) where value > 0;
  select greatest(coalesce((select mp.goal from public.metric_prefs mp where mp.user_id = v_uid and mp.metric = 'sleep'), 8), 1)
    into v_need;
  if sb.n >= 3 then
    s_bad := sb.wk < 0.9 * v_need;
    slp := jsonb_build_object('ready', true, 'last', round(sb.last, 2), 'week', round(sb.wk, 2), 'need', v_need, 'bad', s_bad,
      'points', round(100 * least(1, (0.5 * coalesce(sb.last, sb.wk) + 0.5 * sb.wk) / v_need)));
  else
    slp := jsonb_build_object('ready', false, 'n7', coalesce(sb.n, 0), 'need', v_need);
  end if;

  -- Score: HRV 50%, rusthartslag 25%, slaap 25% (eigen weging), over de factoren met genoeg data
  if (hrv->>'ready')::boolean then sum_w := sum_w + 0.5; sum_p := sum_p + 0.5 * (hrv->>'points')::numeric; end if;
  if (rhr->>'ready')::boolean then sum_w := sum_w + 0.25; sum_p := sum_p + 0.25 * (rhr->>'points')::numeric; end if;
  if (hrv->>'ready')::boolean or (rhr->>'ready')::boolean then
    if (slp->>'ready')::boolean then sum_w := sum_w + 0.25; sum_p := sum_p + 0.25 * (slp->>'points')::numeric; end if;
    v_score := round(sum_p / sum_w);
    uit := (case when (hrv->>'bad')::boolean then 1 else 0 end) + (case when (rhr->>'bad')::boolean then 1 else 0 end);
    -- Oordeel uit de banden: 0 goed hersteld, 1 let op, 2 rustig aan
    v_stand := case when uit = 2 or (uit = 1 and s_bad) then 2 when uit = 1 or s_bad then 1 else 0 end;
  end if;

  -- Verloop van de laatste 30 dagen: HRV 7 dagen met normaal bereik (de grafiek uit het HRV-onderzoek)
  with x as (
    select day, ln(value) v from public.resolved_series('hrv', p_today - 95, p_today) where value > 0
  ), r as (
    select day,
      avg(v) over w7 v7, count(*) over w7 n7,
      avg(v) over wb mu, stddev_samp(v) over wb sd, count(*) over wb nb
    from x
    window w7 as (order by day range between interval '6 days' preceding and current row),
           wb as (order by day range between interval '66 days' preceding and interval '7 days' preceding)
  )
  select coalesce(jsonb_agg(jsonb_build_object('day', day, 'value', round(exp(v7), 1),
           'low', case when nb >= 14 and sd > 0 then round(exp(mu - 0.5 * sd), 1) end,
           'high', case when nb >= 14 and sd > 0 then round(exp(mu + 0.5 * sd), 1) end) order by day), '[]')
    into trend from r where day > p_today - 30 and n7 >= 3;

  -- Fitheid: maximale hartslag (Tanaka e.a. 2001) en VO2max-schatting (Uth e.a. 2004)
  select p.birth_date, p.sex into v_birth, v_sex from public.profiles p where p.id = v_uid;
  if v_birth is not null then
    v_age := extract(year from age(p_today, v_birth))::int;
    v_hrmax := round(208 - 0.7 * v_age);
  end if;
  select s.value, s.day into vo2_src, vo2_day
    from public.resolved_series('vo2', p_today - 180, p_today) s order by s.day desc limit 1;
  pf := case v_sex when 'male' then 15.3 when 'female' then 14.5 else 15 end;
  if v_hrmax is not null and coalesce(rb.n7, 0) >= 3 and rb.v7 > 0 then
    vo2_est := round(pf * v_hrmax / rb.v7, 1);
  end if;

  return jsonb_build_object(
    'herstel', jsonb_build_object('score', v_score, 'stand', v_stand, 'hrv', hrv, 'rhr', rhr, 'sleep', slp, 'trend', trend),
    'fitheid', jsonb_build_object(
      'age', v_age, 'sex', v_sex, 'hrmax', v_hrmax,
      'zones', case when v_hrmax is not null then (
        select jsonb_agg(jsonb_build_object('zone', z, 'from', round(v_hrmax * (0.4 + 0.1 * z)), 'to', round(v_hrmax * (0.5 + 0.1 * z))) order by z)
        from generate_series(1, 5) z) end,
      'rhr7', case when coalesce(rb.n7, 0) >= 3 then round(rb.v7, 1) end,
      'vo2_source', vo2_src, 'vo2_day', vo2_day, 'vo2_estimate', vo2_est, 'vo2_factor', pf));
end $$;

revoke all on function public.fitness_band(numeric, numeric, numeric, bigint, bigint, boolean, boolean) from public, anon;
grant execute on function public.fitness_band(numeric, numeric, numeric, bigint, bigint, boolean, boolean) to authenticated;
revoke all on function public.fitness(date) from public, anon;
grant execute on function public.fitness(date) to authenticated;

-- app_state geeft herstel en fitheid mee, zodat de app nog steeds met één verzoek opent
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
    'fitness', public.fitness(p_to)
  );
end $function$;
