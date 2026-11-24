-- Belastingtest Vital Connect (database), als voorproef voor 1 miljoen gebruikers.
-- Vult 500 gewone gebruikers (60 dagen) en 1 zware gebruiker (5 jaar, 2 bronnen), meet de verzoeken die de app
-- en de servertaken doen, toont de queryplannen en simuleert een dag van een gemiddelde gebruiker.
-- Alles wordt aan het eind teruggedraaid; het resultaat komt als foutmelding "RAPPORT [...]".
do $$
declare rep jsonb := '[]'; t0 timestamptz; ms numeric; heavy uuid; u uuid; n bigint; plan text; r record; i int;
begin
  create temp table tu on commit drop as select gen_random_uuid() id, g from generate_series(1,500) g;
  insert into auth.users (instance_id, id, aud, role, email, raw_app_meta_data, raw_user_meta_data, created_at, updated_at)
  select '00000000-0000-0000-0000-000000000000', id, 'authenticated', 'authenticated', 'load'||g||'@vitalconnect.invalid', '{}', '{}', now(), now() from tu;
  t0 := clock_timestamp();
  insert into public.daily_metrics (user_id, day, metric, source, provider, value, updated_at)
  select tu.id, d::date, m.id, 'garmin', 'garmin', round(m.min_value + (m.max_value - m.min_value) * 0.3, 1), now()
  from tu cross join generate_series(current_date - 59, current_date, interval '1 day') d cross join public.metrics m;
  get diagnostics n = row_count;
  rep := rep || jsonb_build_object('c','vullen 500 gebruikers','d', n||' rijen in '||round(extract(epoch from clock_timestamp()-t0))||' s');
  insert into public.connections (user_id, provider, status, last_sync_at) select id, 'google_health', 'active', now() - (g||' minutes')::interval from tu;
  heavy := (select id from tu where g = 1);
  insert into public.daily_metrics (user_id, day, metric, source, provider, value, updated_at)
  select heavy, d::date, m.id, s.src, s.prov, round(m.min_value + (m.max_value - m.min_value) * 0.3, 1), now()
  from generate_series(current_date - 1825, current_date - 60, interval '1 day') d cross join public.metrics m
  cross join (values ('garmin','garmin'),('health_connect:x','health_connect')) s(src,prov);
  analyze public.daily_metrics; analyze public.connections;

  -- Servertaak: welke Google Health-gebruikers zijn aan de beurt (moet de index connections_due gebruiken)
  t0 := clock_timestamp();
  perform public.provider_due('google_health', 0, 1, 200, '3 hours');
  rep := rep || jsonb_build_object('c','cron-selectie (200 oudste, 3 uur oud)','d', round(extract(epoch from clock_timestamp()-t0)*1000,1)||' ms');
  plan := '';
  for r in execute 'explain select c.user_id from public.connections c where c.provider = ''google_health'' and c.status = ''active'' and (c.last_sync_at is null or c.last_sync_at < now() - interval ''3 hours'') order by c.last_sync_at nulls first limit 200' loop
    plan := plan || r."QUERY PLAN" || ' | ';
  end loop;
  rep := rep || jsonb_build_object('c','cron-selectie gebruikt index','ok', plan like '%connections_due%', 'd', left(plan, 160));

  -- App openen: 20 willekeurige gewone gebruikers, één verzoek
  ms := 0;
  for i in 1..20 loop
    n := 2 + (random()*497)::int;
    u := (select id from tu where g = n);
    perform set_config('request.jwt.claims', jsonb_build_object('sub', u, 'role', 'authenticated')::text, true);
    perform set_config('role', 'authenticated', true);
    t0 := clock_timestamp();
    perform public.app_state(current_date - 13, current_date, true);
    ms := greatest(ms, extract(epoch from clock_timestamp()-t0)*1000);
    perform set_config('role', 'postgres', true);
  end loop;
  rep := rep || jsonb_build_object('c','app openen, gewone gebruiker (1 verzoek, traagste van 20)','ok', ms < 300, 'd', round(ms,1)||' ms');

  -- Zware gebruiker: statistieken eerst berekend, daarna uit de cache
  perform set_config('request.jwt.claims', jsonb_build_object('sub', heavy, 'role', 'authenticated')::text, true);
  perform set_config('role', 'authenticated', true);
  t0 := clock_timestamp(); perform * from public.metric_stats(); ms := extract(epoch from clock_timestamp()-t0)*1000;
  rep := rep || jsonb_build_object('c','statistieken zware gebruiker, 1e keer van de dag','ok', ms < 300, 'd', round(ms,1)||' ms');
  t0 := clock_timestamp(); perform * from public.metric_stats(); ms := extract(epoch from clock_timestamp()-t0)*1000;
  rep := rep || jsonb_build_object('c','statistieken zware gebruiker, daarna (cache)','ok', ms < 20, 'd', round(ms,1)||' ms');

  -- Een dag van een gemiddelde gebruiker: 24 achtergrond-uploads (elk ook een keer dubbel, zoals bij app + achtergrond),
  -- 5 keer openen en 3 keer handmatig vernieuwen
  t0 := clock_timestamp();
  for i in 1..24 loop
    perform public.ingest_health_connect(jsonb_build_array(jsonb_build_object('day', current_date, 'metric','steps','origin','app','value', i*500)));
    perform public.ingest_health_connect(jsonb_build_array(jsonb_build_object('day', current_date, 'metric','steps','origin','app','value', i*500)));
    if i in (7, 8, 12, 13, 18, 20, 21, 22) then perform public.app_state(current_date - 13, current_date, true); end if;
  end loop;
  ms := extract(epoch from clock_timestamp()-t0)*1000;
  rep := rep || jsonb_build_object('c','dag van gemiddelde gebruiker (48 uploads, 8x openen/vernieuwen)','ok', ms < 1000, 'd', round(ms,1)||' ms totaal');
  select value into ms from public.daily_metrics where day = current_date and metric = 'steps' and source = 'health_connect:app';
  rep := rep || jsonb_build_object('c','stappen vandaag na de dag (verwacht 12000)','ok', ms = 12000, 'd', ms);

  -- Klopt de cache + vandaag met een volledige herberekening?
  select count(*) into n from (
    select s.metric, s.days, s.avg_all, s.min_all, s.max_all, s.total_all, s.avg_30, s.last_value from public.metric_stats() s
    except
    select rr.metric, count(*), round(avg(rr.value), 2), min(rr.value), max(rr.value), sum(rr.value),
           round(avg(rr.value) filter (where rr.day > current_date - 30), 2), (array_agg(rr.value order by rr.day desc))[1]
    from (select distinct on (d.metric, d.day) d.metric, d.day, d.value from public.daily_metrics d
          join public.providers pr on pr.id = d.provider
          left join public.metric_prefs mp on mp.user_id = d.user_id and mp.metric = d.metric
          where d.user_id = heavy
          order by d.metric, d.day, (mp.preferred_source is not null and (d.source = mp.preferred_source or d.provider = mp.preferred_source)) desc,
                   pr.priority, d.updated_at desc) rr
    group by rr.metric) x;
  perform set_config('role', 'postgres', true);
  rep := rep || jsonb_build_object('c','statistieken uit cache gelijk aan volledige berekening','ok', n = 0, 'd', n||' verschillen');
  select count(*) into n from public.sync_runs where user_id = heavy;
  rep := rep || jsonb_build_object('c','logregels sync_runs na 1 dag (1 gebruiker)','ok', n = 0, 'd', n);
  raise exception 'RAPPORT %', jsonb_pretty(rep);
end $$;
