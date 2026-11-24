-- Chaostest Vital Connect (database): onzin, randgevallen en verkeerde volgordes op de functies die de app aanroept.
-- Alles in één transactie die wordt teruggedraaid; het resultaat komt als foutmelding "RAPPORT [...]".
do $$
declare
  a uuid := gen_random_uuid();
  b uuid := gen_random_uuid();
  rep jsonb := '[]';
  n bigint; v numeric; j jsonb; ok boolean; t1 timestamptz; t2 timestamptz;
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

  perform set_config('request.jwt.claims', jsonb_build_object('sub', a, 'role', 'authenticated')::text, true);

  -- Lege gebruiker
  select count(*) into n from public.metric_stats();
  rep := rep || jsonb_build_object('c','statistieken zonder data: leeg, geen fout','ok', n = 0);
  j := public.app_state(current_date - 13, current_date, true);
  rep := rep || jsonb_build_object('c','app_state zonder data werkt','ok', j ? 'daily' and jsonb_array_length(j->'daily') = 0);

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

  -- Een andere gebruiker blijft onaangeroerd
  perform set_config('request.jwt.claims', jsonb_build_object('sub', b, 'role', 'authenticated')::text, true);
  j := public.app_state(current_date - 400, current_date, true);
  rep := rep || jsonb_build_object('c','gebruiker B ziet niets van A','ok', jsonb_array_length(j->'daily') = 0 and jsonb_array_length(j->'stats') = 0);

  -- Account verwijderen ruimt ook de cache op (koppeling met cascade)
  perform set_config('role', 'postgres', true);
  select count(*) into n from pg_constraint where conrelid = 'public.metric_stats_cache'::regclass and contype = 'f' and confdeltype = 'c';
  rep := rep || jsonb_build_object('c','cache wordt mee verwijderd met het account','ok', n = 1);

  raise exception 'RAPPORT %', jsonb_pretty(rep);
end $$;
