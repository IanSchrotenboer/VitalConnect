-- Stresstest en isolatietest Vital Connect (database).
-- Draai in Supabase (execute_sql of SQL Editor). Maakt twee tijdelijke testgebruikers
-- (…@vitalconnect.invalid), vult er één met 5 jaar data uit twee bronnen, meet de functies die de app gebruikt,
-- probeert als gebruiker A bij de gegevens van B te komen en test ongeldige invoer.
-- Alles gebeurt in één transactie die aan het eind wordt teruggedraaid: er blijft niets achter in de database.
-- Het resultaat komt daarom als foutmelding terug: "RAPPORT [...]" met één regel per controle (ok = true is goed).

do $$
declare
  a uuid := gen_random_uuid();
  b uuid := gen_random_uuid();
  tag text := to_char(clock_timestamp(), 'YYYYMMDDHH24MISSMS');
  rep jsonb := '[]';
  t0 timestamptz;
  ms numeric;
  maxms numeric;
  n bigint;
  payload jsonb;
  grens constant numeric := 300;   -- ms per aanroep
begin
  -- ---------- Opzet ----------
  insert into auth.users (instance_id, id, aud, role, email, raw_app_meta_data, raw_user_meta_data, created_at, updated_at)
  values ('00000000-0000-0000-0000-000000000000', a, 'authenticated', 'authenticated', 'stresstest-a-' || tag || '@vitalconnect.invalid', '{}', '{"full_name":"Stresstest A"}', now(), now()),
         ('00000000-0000-0000-0000-000000000000', b, 'authenticated', 'authenticated', 'stresstest-b-' || tag || '@vitalconnect.invalid', '{}', '{"full_name":"Stresstest B"}', now(), now());

  -- A: 5 jaar × alle metrics × 2 bronnen; B: 30 dagen Garmin.
  insert into public.daily_metrics (user_id, day, metric, source, provider, value, updated_at)
  select u.id, d::date, m.id, s.source, s.provider,
         round(m.min_value + (m.max_value - m.min_value) * (0.15 + 0.25 * random())::numeric, 1), now()
  from (values (a, 1826), (b, 30)) u(id, days)
  cross join lateral generate_series(current_date - (u.days - 1), current_date, interval '1 day') d
  cross join public.metrics m
  cross join (values ('garmin', 'garmin'), ('health_connect:com.google.android.apps.fitness', 'health_connect')) s(source, provider)
  where u.id = a or s.provider = 'garmin';
  get diagnostics n = row_count;
  rep := rep || jsonb_build_object('c', 'Testdata aangemaakt', 'ok', n > 50000, 'd', n || ' dagwaarden (A: 5 jaar, 2 bronnen; B: 30 dagen)');

  -- A: 5 jaar trainingen (bijna elke dag één, plus kopieën uit een tweede bron die als dubbel wegvallen)
  update public.profiles set birth_date = '1985-06-01', sex = 'male' where id = a;
  insert into public.workouts (user_id, source, external_id, sport, day, started_at, duration_s, distance_m, kcal, avg_hr, max_hr)
  select a, s.src, s.src || '-' || g, (array['running','cycling','strength','yoga'])[1 + g % 4], current_date - g,
         (current_date - g)::timestamptz + interval '7 hours' + s.shift, 1800 + (g % 5) * 600, 8000 + (g % 7) * 1000, 400 + g % 300, 120 + g % 40, 170
  from generate_series(0, 1825) g
  cross join (values ('health_connect', interval '0'), ('google_health', interval '2 minutes')) s(src, shift)
  where g % 6 <> 0;
  get diagnostics n = row_count;
  rep := rep || jsonb_build_object('c', 'Trainingen aangemaakt', 'ok', n > 2500, 'd', n || ' trainingen (5 jaar, 2 bronnen)');

  begin
    -- ---------- Als gebruiker A ----------
    perform set_config('request.jwt.claims', jsonb_build_object('sub', a, 'role', 'authenticated')::text, true);
    perform set_config('role', 'authenticated', true);

    -- Snelheid: elke functie 3 keer, de traagste telt.
    maxms := 0;
    for i in 1..3 loop
      t0 := clock_timestamp(); perform * from public.resolved_daily(current_date - 13, current_date);
      maxms := greatest(maxms, extract(epoch from clock_timestamp() - t0) * 1000);
    end loop;
    rep := rep || jsonb_build_object('c', 'Vandaag laden (resolved_daily, 14 dagen)', 'ok', maxms < grens, 'd', round(maxms) || ' ms');

    maxms := 0;
    for i in 1..3 loop
      t0 := clock_timestamp(); perform * from public.resolved_series('steps', current_date - 729, current_date);
      maxms := greatest(maxms, extract(epoch from clock_timestamp() - t0) * 1000);
    end loop;
    rep := rep || jsonb_build_object('c', 'Grafiek 1 jaar + vorig jaar (resolved_series)', 'ok', maxms < grens, 'd', round(maxms) || ' ms');

    maxms := 0;
    for i in 1..3 loop
      t0 := clock_timestamp(); perform * from public.metric_stats();
      maxms := greatest(maxms, extract(epoch from clock_timestamp() - t0) * 1000);
    end loop;
    rep := rep || jsonb_build_object('c', 'Gezondheid, alle statistieken (metric_stats, 5 jaar)', 'ok', maxms < grens, 'd', round(maxms) || ' ms');

    maxms := 0;
    for i in 1..3 loop
      t0 := clock_timestamp(); perform public.app_state(current_date - 13, current_date, true);
      maxms := greatest(maxms, extract(epoch from clock_timestamp() - t0) * 1000);
    end loop;
    rep := rep || jsonb_build_object('c', 'App openen in één verzoek (app_state, 5 jaar)', 'ok', maxms < grens, 'd', round(maxms) || ' ms');

    maxms := 0;
    for i in 1..3 loop
      t0 := clock_timestamp(); perform public.fitness(current_date);
      maxms := greatest(maxms, extract(epoch from clock_timestamp() - t0) * 1000);
    end loop;
    rep := rep || jsonb_build_object('c', 'Herstel en fitheid (fitness, 5 jaar)', 'ok', maxms < grens, 'd', round(maxms) || ' ms');

    maxms := 0;
    for i in 1..3 loop
      t0 := clock_timestamp(); payload := public.training(current_date);
      maxms := greatest(maxms, extract(epoch from clock_timestamp() - t0) * 1000);
    end loop;
    rep := rep || jsonb_build_object('c', 'Training: lijst, week en belasting (training, 5 jaar)', 'ok', maxms < grens
      and (payload #>> '{load,ready}')::boolean and jsonb_array_length(payload->'workouts') between 50 and 300,
      'd', round(maxms) || ' ms, ' || jsonb_array_length(payload->'workouts') || ' trainingen zichtbaar na ontdubbelen');

    maxms := 0;
    for i in 1..3 loop
      t0 := clock_timestamp(); perform public.workouts_before(current_date - 1000, 100);
      maxms := greatest(maxms, extract(epoch from clock_timestamp() - t0) * 1000);
    end loop;
    rep := rep || jsonb_build_object('c', 'Oudere trainingen laden (workouts_before)', 'ok', maxms < grens, 'd', round(maxms) || ' ms');

    t0 := clock_timestamp();
    select public.save_workouts(jsonb_agg(jsonb_build_object('source','file','external_id','fit-stress-' || g,'sport','running',
      'day', current_date - g % 1800, 'started_at', (current_date - g % 1800)::timestamptz + interval '18 hours' + (g / 1800) * interval '1 hour', 'duration_s', 3600, 'avg_hr', 150)))
      into n from generate_series(0, 1999) g;
    ms := extract(epoch from clock_timestamp() - t0) * 1000;
    rep := rep || jsonb_build_object('c', 'Grote import (save_workouts, 2000 trainingen)', 'ok', n = 2000 and ms < 3000, 'd', n || ' opgeslagen in ' || round(ms) || ' ms');

    t0 := clock_timestamp(); perform public.export_my_data();
    ms := extract(epoch from clock_timestamp() - t0) * 1000;
    rep := rep || jsonb_build_object('c', 'Data exporteren (export_my_data, ~60.000 regels)', 'ok', ms < 3000, 'd', round(ms) || ' ms (grens 3000)');

    -- Grootst toegestane upload vanaf de telefoon: 10.000 dagwaarden.
    select jsonb_agg(jsonb_build_object('day', current_date - (g % 700), 'metric', 'steps', 'origin', 'app' || (g / 700), 'value', 5000 + g))
      into payload from generate_series(0, 9999) g;
    t0 := clock_timestamp(); n := public.ingest_health_connect(payload);
    ms := extract(epoch from clock_timestamp() - t0) * 1000;
    rep := rep || jsonb_build_object('c', 'Health Connect-upload, 10.000 regels', 'ok', n = 10000 and ms < 5000, 'd', n || ' opgeslagen in ' || round(ms) || ' ms');

    -- ---------- Isolatie: A mag niets van B zien of wijzigen ----------
    select count(*) into n from public.daily_metrics where user_id = b;
    rep := rep || jsonb_build_object('c', 'A ziet geen dagwaarden van B', 'ok', n = 0, 'd', n || ' zichtbaar');
    select count(*) into n from public.profiles where id <> a;
    rep := rep || jsonb_build_object('c', 'A ziet geen andere profielen', 'ok', n = 0, 'd', n || ' zichtbaar');
    select count(*) into n from public.metric_prefs where user_id <> a;
    rep := rep || jsonb_build_object('c', 'A ziet geen dashboards van anderen', 'ok', n = 0, 'd', n || ' zichtbaar');
    select count(*) into n from public.workouts where user_id <> a;
    rep := rep || jsonb_build_object('c', 'A ziet geen trainingen van anderen', 'ok', n = 0, 'd', n || ' zichtbaar');
    begin
      insert into public.workouts (user_id, source, external_id, sport, day, started_at, duration_s) values (b, 'manual', 'hack', 'running', current_date, now(), 600);
      rep := rep || jsonb_build_object('c', 'A kan geen training in het account van B zetten', 'ok', false, 'd', 'insert gelukt');
    exception when others then
      rep := rep || jsonb_build_object('c', 'A kan geen training in het account van B zetten', 'ok', true, 'd', 'geweigerd');
    end;
    update public.profiles set display_name = 'gehackt', weight_kg = 99 where id = b;
    get diagnostics n = row_count;
    rep := rep || jsonb_build_object('c', 'A kan het profiel van B niet wijzigen', 'ok', n = 0, 'd', n || ' rijen gewijzigd');
    update public.metric_prefs set goal = 1 where user_id = b;
    get diagnostics n = row_count;
    rep := rep || jsonb_build_object('c', 'A kan het dashboard van B niet wijzigen', 'ok', n = 0, 'd', n || ' rijen gewijzigd');
    begin
      insert into public.daily_metrics (user_id, day, metric, source, provider, value, updated_at)
      values (b, current_date, 'steps', 'garmin', 'garmin', 1, now());
      rep := rep || jsonb_build_object('c', 'A kan geen data in het account van B zetten', 'ok', false, 'd', 'insert gelukt');
    exception when others then
      rep := rep || jsonb_build_object('c', 'A kan geen data in het account van B zetten', 'ok', true, 'd', 'geweigerd: ' || sqlerrm);
    end;
    begin
      perform * from public.oauth_tokens;
      rep := rep || jsonb_build_object('c', 'Versleutelde tokens onbereikbaar voor de app', 'ok', false, 'd', 'leesbaar');
    exception when others then
      rep := rep || jsonb_build_object('c', 'Versleutelde tokens onbereikbaar voor de app', 'ok', true, 'd', 'geweigerd');
    end;
    rep := rep || jsonb_build_object('c', 'Export bevat alleen eigen data', 'ok',
      position(b::text in public.export_my_data()::text) = 0, 'd', 'id van B niet in export van A');

    -- ---------- Ongeldige invoer ----------
    begin
      update public.profiles set height_cm = 999 where id = a;
      rep := rep || jsonb_build_object('c', 'Lengte 999 cm wordt geweigerd', 'ok', false, 'd', 'opgeslagen');
    exception when check_violation or numeric_value_out_of_range then
      rep := rep || jsonb_build_object('c', 'Lengte 999 cm wordt geweigerd', 'ok', true, 'd', 'geweigerd');
    end;
    begin
      update public.profiles set birth_date = current_date + 1 where id = a;
      rep := rep || jsonb_build_object('c', 'Geboortedatum in de toekomst wordt geweigerd', 'ok', false, 'd', 'opgeslagen');
    exception when check_violation then
      rep := rep || jsonb_build_object('c', 'Geboortedatum in de toekomst wordt geweigerd', 'ok', true, 'd', 'geweigerd');
    end;
    begin
      update public.profiles set theme = 'paars' where id = a;
      rep := rep || jsonb_build_object('c', 'Onbekende weergave wordt geweigerd', 'ok', false, 'd', 'opgeslagen');
    exception when check_violation then
      rep := rep || jsonb_build_object('c', 'Onbekende weergave wordt geweigerd', 'ok', true, 'd', 'geweigerd');
    end;
    begin
      update public.profiles set email = 'ander@vitalconnect.invalid' where id = a;
      rep := rep || jsonb_build_object('c', 'E-mailadres niet via de app te wijzigen', 'ok', false, 'd', 'gewijzigd');
    exception when insufficient_privilege then
      rep := rep || jsonb_build_object('c', 'E-mailadres niet via de app te wijzigen', 'ok', true, 'd', 'geweigerd');
    end;
    update public.profiles set display_name = 'Stresstest', birth_date = '1990-01-01', sex = 'other', height_cm = 180.5, weight_kg = 75.2, theme = 'dark', onboarded_at = now() where id = a;
    get diagnostics n = row_count;
    rep := rep || jsonb_build_object('c', 'Geldig profiel opslaan', 'ok', n = 1, 'd', n || ' rij');
    n := public.ingest_health_connect(('[{"day":"2020-01-01","metric":"steps","origin":"x","value":1},{"day":"' || current_date || '","metric":"steps","origin":"x","value":-5},{"day":"' || current_date || '","metric":"bestaat_niet","origin":"x","value":1},{"day":"' || current_date || '","metric":"steps","origin":"x;--","value":1}]')::jsonb);
    rep := rep || jsonb_build_object('c', 'Upload filtert ongeldige regels (te oud, negatief, onbekend, rare bron)', 'ok', n = 0, 'd', n || ' opgeslagen');
    begin
      perform public.ingest_health_connect('{"geen":"lijst"}'::jsonb);
      rep := rep || jsonb_build_object('c', 'Upload weigert een verkeerd formaat', 'ok', false, 'd', 'geaccepteerd');
    exception when others then
      rep := rep || jsonb_build_object('c', 'Upload weigert een verkeerd formaat', 'ok', true, 'd', 'geweigerd');
    end;
  exception when others then
    perform set_config('role', 'postgres', true);
    rep := rep || jsonb_build_object('c', 'Onverwachte fout tijdens de test', 'ok', false, 'd', sqlerrm);
  end;

  -- ---------- Account verwijderen wist alles ----------
  perform set_config('role', 'postgres', true);
  perform set_config('request.jwt.claims', '', true);
  select count(*) into n
  from pg_constraint c join pg_namespace ns on ns.oid = c.connamespace
  where ns.nspname = 'public' and c.contype = 'f' and c.confrelid = 'auth.users'::regclass and c.confdeltype <> 'c';
  rep := rep || jsonb_build_object('c', 'Account verwijderen wist alle data (alle koppelingen aan de gebruiker met cascade)', 'ok', n = 0,
    'd', n || ' tabellen zonder cascade');

  -- Terugdraaien: de testgebruikers en al hun data verdwijnen weer.
  raise exception 'RAPPORT %', jsonb_pretty(rep);
end $$;
