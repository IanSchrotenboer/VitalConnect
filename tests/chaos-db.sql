-- Chaostest Vital Connect (database): onzin, randgevallen en verkeerde volgordes op de functies die de app aanroept.
-- Alles wordt teruggedraaid; het resultaat komt terug als tabel (controle, ok, detail).
-- Zo staat de test niet meer als "Execute SQL FAILED" in de logs van Supabase.
create temp table if not exists testrapport (r jsonb);
delete from pg_temp.testrapport;
do $$
declare
  a uuid := gen_random_uuid();
  b uuid := gen_random_uuid();
  rep jsonb := '[]';
  n bigint; k bigint; v numeric; j jsonb; ok boolean; ids uuid[]; t1 timestamptz; t2 timestamptz;
begin
  begin
  insert into auth.users (instance_id, id, aud, role, email, raw_app_meta_data, raw_user_meta_data, created_at, updated_at)
  values ('00000000-0000-0000-0000-000000000000', a, 'authenticated', 'authenticated', 'chaos-a@vitalconnect.invalid', '{}', '{}', now(), now()),
         ('00000000-0000-0000-0000-000000000000', b, 'authenticated', 'authenticated', 'chaos-b@vitalconnect.invalid', '{}', '{}', now(), now());

  -- Zonder inlog
  perform set_config('request.jwt.claims', '{"role":"authenticated"}', true);
  perform set_config('role', 'authenticated', true);
  begin perform public.app_state(current_date, current_date, true); ok := false; exception when others then ok := true; end;
  rep := rep || jsonb_build_object('c','app_state zonder inlog geweigerd','ok', ok);
  begin perform public.ingest_health_connect('[]'::jsonb); ok := false; exception when others then ok := true; end;
  rep := rep || jsonb_build_object('c','upload zonder inlog geweigerd','ok', ok);
  select count(*) into n from public.metric_stats();
  rep := rep || jsonb_build_object('c','statistieken zonder inlog leeg','ok', n = 0, 'd', n);
  begin perform public.fitness(current_date); ok := false; exception when others then ok := true; end;
  rep := rep || jsonb_build_object('c','herstel zonder inlog geweigerd','ok', ok);
  begin perform public.training(current_date); perform public.save_workouts('[]'::jsonb); ok := false; exception when others then ok := true; end;
  rep := rep || jsonb_build_object('c','trainingen zonder inlog geweigerd','ok', ok);

  perform set_config('request.jwt.claims', jsonb_build_object('sub', a, 'role', 'authenticated')::text, true);

  -- Lege gebruiker
  select count(*) into n from public.metric_stats();
  rep := rep || jsonb_build_object('c','statistieken zonder data: leeg, geen fout','ok', n = 0);
  j := public.app_state(current_date - 13, current_date, true);
  rep := rep || jsonb_build_object('c','app_state zonder data werkt','ok', j ? 'daily' and jsonb_array_length(j->'daily') = 0);
  rep := rep || jsonb_build_object('c','herstel zonder data: nog aan het leren, geen fout','ok',
    j #> '{fitness,herstel,score}' = 'null'::jsonb and j #>> '{fitness,herstel,hrv,ready}' = 'false');
  begin perform public.fitness(null); perform public.fitness('1900-01-01'); perform public.fitness('9999-12-31'); ok := true;
  exception when others then ok := false; end;
  rep := rep || jsonb_build_object('c','herstel met vreemde datums: geen fout','ok', ok);

  -- Verkeerde perioden
  begin perform public.app_state(current_date, current_date - 1, true); ok := false; exception when others then ok := true; end;
  rep := rep || jsonb_build_object('c','periode achterstevoren geweigerd','ok', ok);
  begin perform public.app_state(current_date - 1000, current_date, true); ok := false; exception when others then ok := true; end;
  rep := rep || jsonb_build_object('c','periode > 400 dagen geweigerd','ok', ok);

  -- Rommel in één upload: alleen de geldige regel mag blijven, en de upload mag niet in zijn geheel mislukken
  begin
    n := public.ingest_health_connect(jsonb_build_array(
      jsonb_build_object('day', current_date, 'metric', 'steps', 'origin', 'goed', 'value', 4321),
      jsonb_build_object('day', current_date, 'metric', 'steps', 'origin', 'nan', 'value', 'NaN'),
      jsonb_build_object('day', current_date, 'metric', 'steps', 'origin', 'inf', 'value', 'Infinity'),
      jsonb_build_object('day', current_date, 'metric', 'steps', 'origin', 'tekst', 'value', 'veel'),
      jsonb_build_object('day', current_date, 'metric', 'steps', 'origin', 'leeg', 'value', null),
      jsonb_build_object('day', '2026-13-45', 'metric', 'steps', 'origin', 'datum', 'value', 1),
      jsonb_build_object('day', 'gisteren', 'metric', 'steps', 'origin', 'datum2', 'value', 1),
      jsonb_build_object('day', current_date + 30, 'metric', 'steps', 'origin', 'toekomst', 'value', 1),
      jsonb_build_object('day', current_date, 'metric', 'steps', 'origin', 'ünïcødé', 'value', 1),
      jsonb_build_object('day', current_date, 'metric', 'steps', 'origin', repeat('x', 300), 'value', 1),
      jsonb_build_object('day', current_date, 'metric', 'steps', 'value', 1),
      jsonb_build_object('metric', 'steps', 'origin', 'geendag', 'value', 1),
      jsonb_build_object('day', current_date, 'metric', 'steps', 'origin', 'groot', 'value', 1e30),
      '"geen object"'::jsonb, '[1,2,3]'::jsonb, 'null'::jsonb));
    rep := rep || jsonb_build_object('c','upload met rommel: alleen de geldige regel opgeslagen','ok', n = 1, 'd', n);
  exception when others then
    rep := rep || jsonb_build_object('c','upload met rommel: alleen de geldige regel opgeslagen','ok', false, 'd', 'hele upload mislukt: ' || sqlerrm);
  end;

  -- Dezelfde dag twee keer in één upload: één waarde, geen fout
  begin
    n := public.ingest_health_connect(jsonb_build_array(
      jsonb_build_object('day', current_date, 'metric', 'kcal', 'origin', 'dubbel', 'value', 100),
      jsonb_build_object('day', current_date, 'metric', 'kcal', 'origin', 'dubbel', 'value', 200)));
    rep := rep || jsonb_build_object('c','dubbele dag in één upload','ok', n = 1, 'd', n);
  exception when others then rep := rep || jsonb_build_object('c','dubbele dag in één upload','ok', false, 'd', sqlerrm);
  end;

  -- Te grote upload
  begin perform public.ingest_health_connect((select jsonb_agg(jsonb_build_object('day', current_date, 'metric','steps','origin','x'||g,'value',1)) from generate_series(1,10001) g)); ok := false;
  exception when others then ok := true; end;
  rep := rep || jsonb_build_object('c','upload van 10.001 regels geweigerd','ok', ok);

  -- Statistieken blijven kloppen bij wijzigingen in het verleden, een andere voorkeursbron en een nieuwe dag
  perform public.ingest_health_connect((select jsonb_agg(jsonb_build_object('day', current_date - g, 'metric','steps','origin','hist','value', 1000 + g)) from generate_series(1, 40) g));
  select total_all into v from public.metric_stats() where metric = 'steps';
  perform public.ingest_health_connect(jsonb_build_array(jsonb_build_object('day', current_date - 5, 'metric','steps','origin','hist','value', 99999)));
  select total_all - v into v from public.metric_stats() where metric = 'steps';
  rep := rep || jsonb_build_object('c','wijziging in het verleden zichtbaar in statistieken','ok', v = 99999 - 1005, 'd', v);
  perform set_config('role', 'postgres', true);
  insert into public.daily_metrics (user_id, day, metric, source, provider, value, updated_at)
  values (a, current_date - 3, 'steps', 'garmin', 'garmin', 7, now());
  update public.metric_stats_cache set computed_at = now() - interval '1 day' where user_id = a;  -- cache van gisteren
  perform set_config('role', 'authenticated', true);
  select days into n from public.metric_stats() where metric = 'steps';
  rep := rep || jsonb_build_object('c','cache van gisteren wordt vervangen (41 dagen)','ok', n = 41, 'd', n);
  update public.metric_prefs set preferred_source = 'garmin' where user_id = a and metric = 'steps';
  select min_all into v from public.metric_stats() where metric = 'steps';
  rep := rep || jsonb_build_object('c','andere voorkeursbron verandert statistieken direct','ok', v = 7, 'd', v);

  -- Garmin en Google schrijven dezelfde dagen steeds opnieuw: dezelfde waarde mag de cache niet laten vervallen
  perform set_config('role', 'postgres', true);
  insert into public.daily_metrics (user_id, day, metric, source, provider, value, updated_at)
  values (a, current_date - 6, 'kcal', 'google_health', 'google_health', 321, now())
  on conflict (user_id, day, metric, source) do update set value = excluded.value, updated_at = excluded.updated_at;
  select data_changed_at into t1 from public.profiles where id = a;
  insert into public.daily_metrics (user_id, day, metric, source, provider, value, updated_at)
  values (a, current_date - 6, 'kcal', 'google_health', 'google_health', 321, now())
  on conflict (user_id, day, metric, source) do update set value = excluded.value, updated_at = excluded.updated_at;
  select data_changed_at into t2 from public.profiles where id = a;
  rep := rep || jsonb_build_object('c','zelfde waarde opnieuw van Google of Garmin: cache blijft geldig','ok', t1 is not distinct from t2);
  perform set_config('role', 'authenticated', true);

  -- Herstel: constante HRV (standaardafwijking 0) en uitschieters geven geen deling door nul of fout
  perform set_config('role', 'postgres', true);
  insert into public.daily_metrics (user_id, day, metric, source, provider, value, updated_at)
  select a, current_date - g, 'hrv', 'garmin', 'garmin', 50, now() from generate_series(0, 70) g
  on conflict (user_id, day, metric, source) do update set value = excluded.value;
  insert into public.daily_metrics (user_id, day, metric, source, provider, value, updated_at)
  select a, current_date - g, 'rhr', 'garmin', 'garmin', case when g = 3 then 200 when g = 4 then 20 else 55 + g % 3 end, now() from generate_series(0, 70) g
  on conflict (user_id, day, metric, source) do update set value = excluded.value;
  update public.profiles set birth_date = '1900-01-01', sex = 'female' where id = a;
  perform set_config('role', 'authenticated', true);
  begin
    j := public.fitness(current_date);
    rep := rep || jsonb_build_object('c','herstel met constante HRV en uitschieters: geen fout','ok',
      j #>> '{herstel,hrv,ready}' = 'false' and (j #>> '{herstel,score}')::int between 0 and 100 and (j #>> '{fitheid,hrmax}')::int > 0);
  exception when others then
    rep := rep || jsonb_build_object('c','herstel met constante HRV en uitschieters: geen fout','ok', false, 'd', sqlerrm);
  end;

  -- Trainingen: geen lijst = nette weigering; onzin in de lijst wordt overgeslagen, geldige rijen blijven, niemand komt bij een ander
  k := 0;
  begin k := k + coalesce(public.save_workouts(null), 0); exception when others then null; end;
  begin k := k + coalesce(public.save_workouts('{}'::jsonb), 0); exception when others then null; end;
  begin k := k + coalesce(public.save_workouts('"x"'::jsonb), 0); exception when others then null; end;
  rep := rep || jsonb_build_object('c','save_workouts zonder lijst: niets opgeslagen','ok', k = 0 and not exists (select 1 from public.workouts where user_id = a));
  begin
    n := public.save_workouts(jsonb_build_array(
      1, 'x', null, '{}'::jsonb,
      jsonb_build_object('source','manual','external_id','c1','sport','raketbouw','day',current_date,'started_at',now(),'duration_s',600),
      jsonb_build_object('source','manual','external_id','c2','sport','running','day','gisteren','started_at',now(),'duration_s',600),
      jsonb_build_object('source','manual','external_id','c3','sport','running','day',current_date,'started_at','nooit','duration_s',600),
      jsonb_build_object('source','manual','external_id','c4','sport','running','day',current_date,'started_at',now(),'duration_s',5),
      jsonb_build_object('source','manual','external_id','c5','sport','running','day',current_date,'started_at',now(),'duration_s',1e30),
      jsonb_build_object('source','manual','external_id','c6','sport','running','day',current_date,'started_at',now(),'duration_s',600,'avg_hr',-4,'distance_m','NaN'),
      jsonb_build_object('source','hacker','external_id','c7','sport','running','day',current_date,'started_at',now(),'duration_s',600),
      jsonb_build_object('source','manual','external_id',repeat('x',500),'sport','running','day',current_date,'started_at',now(),'duration_s',600),
      jsonb_build_object('source','manual','external_id','c9','sport','running','day','9999-12-31','started_at','9999-12-31T00:00:00Z','duration_s',600),
      jsonb_build_object('source','manual','external_id','ok1','sport','running','title',repeat('t',500),'notes',repeat('n',5000),'day',current_date,'started_at',now(),'duration_s',1800,'avg_hr',150)));
    select count(*) into k from public.workouts where user_id = a and source = 'manual';
    rep := rep || jsonb_build_object('c','save_workouts: onzin overgeslagen, geldige rij opgeslagen en ingekort','ok', n = 1 and k = 1
      and (select length(title) <= 80 and length(notes) <= 1000 from public.workouts where user_id = a and external_id = 'ok1'), 'd', n || ' opgeslagen');
  exception when others then
    rep := rep || jsonb_build_object('c','save_workouts: onzin overgeslagen, geldige rij opgeslagen en ingekort','ok', false, 'd', sqlerrm);
  end;
  begin
    perform public.save_workouts((select jsonb_agg(jsonb_build_object('source','manual','external_id','m'||g,'sport','yoga','day',current_date,'started_at',now(),'duration_s',600)) from generate_series(1,2500) g));
    ok := false;
  exception when others then ok := true; end;
  rep := rep || jsonb_build_object('c','save_workouts weigert meer dan 2000 rijen per keer','ok', ok);
  begin
    j := public.training(null); j := public.training('1900-01-01'); j := public.training('9999-12-31');
    perform public.workouts_before(null, null); perform public.workouts_before(current_date, -5); perform public.workouts_before(current_date, 100000);
    n := public.delete_workouts(null, null, null) + public.delete_workouts(array[gen_random_uuid()], 'hacker', array['x']);
    rep := rep || jsonb_build_object('c','training, workouts_before en delete_workouts met onzin: geen fout','ok', n = 0);
  exception when others then
    rep := rep || jsonb_build_object('c','training, workouts_before en delete_workouts met onzin: geen fout','ok', false, 'd', sqlerrm);
  end;

  -- Welke trainingen meetellen: elke keuze, ontdubbelen alleen binnen wat meetelt, onzin geweigerd
  perform set_config('role', 'postgres', true);
  delete from public.workouts where user_id = a;
  insert into public.workouts (user_id, source, external_id, sport, day, started_at, duration_s, avg_hr, origin, recording) values
    (a, 'google_health', 'g-run', 'running', current_date - 1, (current_date - 1) + time '12:00', 3600, 150, 'FITBIT', 'active'),
    (a, 'google_health', 'g-autowalk', 'walking', current_date - 1, (current_date - 1) + time '15:00', 900, 70, 'FITBIT', 'passive'),
    (a, 'google_health', 'g-fitwalk', 'walking', current_date - 2, (current_date - 2) + time '06:00', 1800, null, 'HEALTH_CONNECT', 'unknown'),
    (a, 'health_connect', 'h-run', 'running', current_date - 1, (current_date - 1) + time '12:01', 3590, null, 'com.google.android.apps.fitness', 'active'),
    (a, 'health_connect', 'h-fitwalk', 'walking', current_date - 2, (current_date - 2) + time '06:00', 1800, null, 'com.google.android.apps.fitness', 'passive'),
    (a, 'manual', 'ok1', 'yoga', current_date - 3, (current_date - 3) + time '20:00', 1200, null, null, 'manual');
  insert into public.connections (user_id, provider, status) values (a, 'google_health', 'active') on conflict do nothing;
  perform set_config('role', 'authenticated', true);
  for k in 1..5 loop
    update public.profiles set training_source = (array['auto','auto','health_connect','all','google_health'])[k],
      training_walk_min = case when k = 2 then 20 else 0 end, birth_date = '1991-04-18' where id = a;
    j := public.training(current_date);
    rep := rep || jsonb_build_object('c', 'trainingen tellen mee volgens instelling ' || (array['auto','auto + wandelingen vanaf 20 min','health_connect','all','google_health'])[k],
      'ok', (select string_agg(x->>'external_id', ',' order by x->>'external_id') from jsonb_array_elements(j->'workouts') x)
            = (array['g-autowalk,g-run,ok1','g-run,ok1','h-fitwalk,h-run,ok1','g-autowalk,g-fitwalk,g-run,ok1','g-autowalk,g-run,ok1'])[k],
      'd', (select string_agg(x->>'external_id', ',' order by x->>'external_id') from jsonb_array_elements(j->'workouts') x));
  end loop;
  begin update public.profiles set training_source = 'strava' where id = a; ok := false; exception when check_violation then ok := true; end;
  begin update public.profiles set training_walk_light = null where id = a; ok := ok and false; exception when not_null_violation then null; end;
  begin update public.profiles set training_walk_min = 15 where id = a; ok := ok and false; exception when check_violation then null; end;
  rep := rep || jsonb_build_object('c','onbekende trainingsbron, minuten en lege schakelaar geweigerd','ok', ok);
  -- Lichte wandelingen: zonder rusthartslag geen grens, dan beslist het tempo (de autowalk van 15 min heeft geen afstand: telt mee)
  update public.profiles set training_source = 'all', training_walk_light = true, birth_date = null where id = a;
  j := public.training(current_date);
  rep := rep || jsonb_build_object('c','lichte wandelingen zonder hartslaggrens: tempo of niets bekend telt mee','ok',
    (select string_agg(x->>'external_id', ',' order by x->>'external_id') from jsonb_array_elements(j->'workouts') x) = 'g-autowalk,g-fitwalk,g-run,ok1'
    and j #> '{filter,hr_limit}' = 'null'::jsonb,
    'd', (select string_agg(x->>'external_id', ',' order by x->>'external_id') from jsonb_array_elements(j->'workouts') x));
  perform set_config('role', 'postgres', true);
  insert into public.daily_metrics (user_id, day, metric, source, provider, value, updated_at)
  select a, current_date - g, 'rhr', 'garmin', 'garmin', 55, now() from generate_series(0, 29) g
  on conflict (user_id, day, metric, source) do update set value = excluded.value;
  perform set_config('role', 'authenticated', true);
  update public.profiles set birth_date = '1991-04-18' where id = a;
  j := public.training(current_date);
  rep := rep || jsonb_build_object('c','lichte wandelingen met hartslaggrens: wandeling met hartslag 70 telt niet mee','ok',
    (select string_agg(x->>'external_id', ',' order by x->>'external_id') from jsonb_array_elements(j->'workouts') x) = 'g-fitwalk,g-run,ok1'
    and (j #>> '{filter,hr_limit}')::int between 80 and 110 and (j #>> '{filter,hidden_walks}')::int = 1,
    'd', (select string_agg(x->>'external_id', ',' order by x->>'external_id') from jsonb_array_elements(j->'workouts') x) || ' | grens ' || coalesce(j #>> '{filter,hr_limit}', '-'));
  update public.profiles set training_source = 'auto', training_walk_min = 0, training_walk_light = false where id = a;
  n := public.save_workouts(jsonb_build_array(jsonb_build_object('source','manual','external_id','m-rec','sport','yoga','day',current_date,
        'started_at',now() - interval '2 hours','duration_s',600,'recording','<script>')));
  rep := rep || jsonb_build_object('c','onbekende vastlegmethode wordt leeg opgeslagen','ok', n = 1
    and (select recording from public.workouts where user_id = a and external_id = 'm-rec') is null);
  delete from public.workouts where user_id = a and external_id <> 'ok1';

  -- Een andere gebruiker blijft onaangeroerd
  select array_agg(id) into ids from public.workouts where user_id = a;
  perform set_config('request.jwt.claims', jsonb_build_object('sub', b, 'role', 'authenticated')::text, true);
  j := public.app_state(current_date - 400, current_date, true);
  rep := rep || jsonb_build_object('c','gebruiker B ziet niets van A','ok', jsonb_array_length(j->'daily') = 0 and jsonb_array_length(j->'stats') = 0
    and j #> '{fitness,herstel,score}' = 'null'::jsonb and j #> '{fitness,fitheid,hrmax}' = 'null'::jsonb
    and jsonb_array_length(j #> '{training,workouts}') = 0);
  n := public.delete_workouts(ids, 'manual', array['ok1']);
  perform set_config('role', 'postgres', true);
  select count(*) into k from public.workouts where user_id = a;
  perform set_config('role', 'authenticated', true);
  rep := rep || jsonb_build_object('c','gebruiker B kan trainingen van A niet verwijderen','ok', n = 0 and k = 1 and cardinality(ids) = 1);

  -- Beheer: alleen beheerders, en alleen gegevens over gebruik (geen gezondheidswaarden)
  perform set_config('request.jwt.claims', jsonb_build_object('sub', b, 'role', 'authenticated', 'aal', 'aal1')::text, true);
  begin perform public.admin_overview(); ok := false; exception when others then ok := sqlerrm like '%beheerders%'; end;
  rep := rep || jsonb_build_object('c','beheeroverzicht geweigerd voor wie geen beheerder is','ok', ok);
  begin update public.profiles set app_version = '<script>' where id = b; ok := false; exception when check_violation then ok := true; end;
  rep := rep || jsonb_build_object('c','onzin als app-versie geweigerd','ok', ok);
  begin update public.profiles set app_seen_at = now() + interval '1 year' where id = b; ok := false; exception when check_violation then ok := true; end;
  rep := rep || jsonb_build_object('c','laatst gezien in de toekomst geweigerd','ok', ok);
  update public.profiles set app_version = '1.19.0', app_seen_at = now() where id = b;
  j := public.app_state(current_date, current_date, false);
  rep := rep || jsonb_build_object('c','app_state geeft versie terug en is_admin onwaar','ok', j #>> '{profile,app_version}' = '1.19.0' and j->>'is_admin' = 'false');
  perform set_config('role', 'postgres', true);
  insert into public.beheerders (user_id) values (a);
  perform set_config('role', 'authenticated', true);
  perform set_config('request.jwt.claims', jsonb_build_object('sub', a, 'role', 'authenticated', 'aal', 'aal1')::text, true);
  j := public.admin_overview();
  rep := rep || jsonb_build_object('c','beheerder ziet het overzicht, zonder gezondheidswaarden','ok',
    (j #>> '{kpi,gebruikers}')::int >= 2 and jsonb_array_length(j->'gebruikers') >= 2
    and j::text not like '%"value"%' and j::text not like '%daily%' and public.app_state(current_date, current_date, false)->>'is_admin' = 'true');

  -- Tweestapsverificatie aan voor B: zonder tweede stap (aal1) kan B niets, met tweede stap (aal2) alles
  perform set_config('role', 'postgres', true);
  insert into auth.mfa_factors (id, user_id, friendly_name, factor_type, status, created_at, updated_at, secret)
  values (gen_random_uuid(), b, 'chaos', 'totp', 'verified', now(), now(), 'x');
  perform set_config('role', 'authenticated', true);
  perform set_config('request.jwt.claims', jsonb_build_object('sub', b, 'role', 'authenticated', 'aal', 'aal1')::text, true);
  begin perform public.app_state(current_date, current_date, true); ok := false; exception when others then ok := sqlerrm = 'Tweede stap nodig'; end;
  rep := rep || jsonb_build_object('c','tweestaps: app_state zonder tweede stap geweigerd','ok', ok);
  begin perform public.ingest_health_connect('[]'::jsonb); ok := false; exception when others then ok := sqlerrm = 'Tweede stap nodig'; end;
  rep := rep || jsonb_build_object('c','tweestaps: upload zonder tweede stap geweigerd','ok', ok);
  select count(*) into n from public.metric_stats();
  select count(*) into k from public.profiles;
  rep := rep || jsonb_build_object('c','tweestaps: zonder tweede stap geen statistieken en geen profiel','ok', n = 0 and k = 0, 'd', n + k);
  update public.profiles set display_name = 'gehackt' where id = b;
  get diagnostics n = row_count;
  -- Alleen de weigering van row level security telt (42501), geen andere fout
  begin insert into public.workouts (user_id, source, external_id, sport, day, started_at, duration_s)
    values (b, 'manual', 'mfa', 'running', current_date, now() - interval '1 hour', 600); ok := false;
  exception when insufficient_privilege then ok := true; end;
  rep := rep || jsonb_build_object('c','tweestaps: zonder tweede stap niets wijzigen of toevoegen','ok', n = 0 and ok);
  perform set_config('request.jwt.claims', jsonb_build_object('sub', b, 'role', 'authenticated', 'aal', 'aal2')::text, true);
  j := public.app_state(current_date, current_date, true);
  select count(*) into k from public.profiles;
  rep := rep || jsonb_build_object('c','tweestaps: met tweede stap werkt alles weer','ok', j ? 'profile' and k = 1);

  -- Herstelcodes: alleen te maken met tweede stap; één goede code zet tweestaps uit; raden wordt na 5 keer geblokkeerd
  declare codes text[]; begin
    codes := public.create_recovery_codes();
    rep := rep || jsonb_build_object('c','herstelcodes: 10 unieke codes','ok', cardinality(codes) = 10
      and (select count(distinct c) from unnest(codes) c) = 10 and (public.app_state(current_date, current_date, false)->>'recovery_codes')::int = 10);
    perform set_config('request.jwt.claims', jsonb_build_object('sub', b, 'role', 'authenticated', 'aal', 'aal1')::text, true);
    begin perform public.create_recovery_codes(); ok := false; exception when others then ok := sqlerrm = 'Tweede stap nodig'; end;
    rep := rep || jsonb_build_object('c','herstelcodes: maken zonder tweede stap geweigerd','ok', ok);
    perform set_config('request.jwt.claims', jsonb_build_object('sub', a, 'role', 'authenticated', 'aal', 'aal1')::text, true);
    ok := not public.use_recovery_code(codes[1]);
    rep := rep || jsonb_build_object('c','herstelcodes: code van B werkt niet voor A','ok', ok);
    perform set_config('request.jwt.claims', jsonb_build_object('sub', b, 'role', 'authenticated', 'aal', 'aal1')::text, true);
    ok := not public.use_recovery_code(null) and not public.use_recovery_code('') and not public.use_recovery_code(repeat('x', 5000));
    rep := rep || jsonb_build_object('c','herstelcodes: leeg, null en onzin geweigerd zonder fout','ok', ok);
    ok := public.use_recovery_code(lower(replace(codes[7], '-', ' ')));
    j := public.app_state(current_date, current_date, false);
    perform set_config('role', 'postgres', true);
    select count(*) into n from auth.mfa_factors where user_id = b;
    select count(*) into k from public.mfa_herstelcodes where user_id = b;
    perform set_config('role', 'authenticated', true);
    rep := rep || jsonb_build_object('c','herstelcodes: goede code zet tweestaps uit en de app opent weer (aal1)','ok', ok and n = 0 and k = 0 and j ? 'profile');
    for i in 1..5 loop perform public.use_recovery_code('AAAAA-AAAAA'); end loop;
    begin perform public.use_recovery_code('AAAAA-AAAAA'); ok := false; exception when others then ok := sqlerrm like 'Te veel pogingen%'; end;
    rep := rep || jsonb_build_object('c','herstelcodes: na 5 foute pogingen een uur geblokkeerd','ok', ok);
  end;

  -- Account verwijderen ruimt ook de cache op (koppeling met cascade)
  perform set_config('role', 'postgres', true);
  select count(*) into n from pg_constraint where conrelid = 'public.metric_stats_cache'::regclass and contype = 'f' and confdeltype = 'c';
  rep := rep || jsonb_build_object('c','cache wordt mee verwijderd met het account','ok', n = 1);

  raise exception 'RAPPORT %', jsonb_pretty(rep);
  -- Alles hierboven wordt teruggedraaid (subtransactie); alleen het rapport blijft over. Echte fouten gaan gewoon door.
  exception when others then
    if sqlerrm not like 'RAPPORT %' then raise; end if;
  end;
  insert into pg_temp.testrapport values (rep);
end $$;
select e->>'c' as controle, (e->>'ok')::boolean as ok, e->>'d' as detail from pg_temp.testrapport, jsonb_array_elements(r) e;
