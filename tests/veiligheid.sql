-- Veiligheidscheck Vital Connect: draai in Supabase (execute_sql of SQL Editor).
-- Geeft één regel per controle: ok = true is goed. Wijzigt niets.
with
tabellen as (
  select c.oid, c.relname, c.relrowsecurity
  from pg_class c join pg_namespace n on n.oid = c.relnamespace
  where n.nspname = 'public' and c.relkind = 'r'
),
functies as (
  select p.oid, p.proname, p.prosecdef, p.proconfig
  from pg_proc p join pg_namespace n on n.oid = p.pronamespace
  where n.nspname = 'public'
)
select 'RLS aan op elke tabel' as controle,
       bool_and(relrowsecurity) as ok,
       coalesce(string_agg(relname, ', ') filter (where not relrowsecurity), '') as detail
from tabellen
union all
select 'Elke tabel met rechten voor ingelogden heeft een policy',
       count(*) = 0,
       coalesce(string_agg(t.relname, ', '), '')
from tabellen t
where has_table_privilege('authenticated', t.oid, 'select,insert,update,delete')
  and not exists (select 1 from pg_policies p where p.schemaname = 'public' and p.tablename = t.relname)
union all
select 'Geen tabelrechten voor anon (niet ingelogd)',
       count(*) = 0,
       coalesce(string_agg(t.relname, ', '), '')
from tabellen t
where has_table_privilege('anon', t.oid, 'select,insert,update,delete')
union all
select 'Geen functies uitvoerbaar door anon',
       count(*) = 0,
       coalesce(string_agg(proname, ', '), '')
from functies f
where has_function_privilege('anon', f.oid, 'execute')
union all
select 'SECURITY DEFINER-functies hebben een vaste search_path',
       count(*) = 0,
       coalesce(string_agg(proname, ', '), '')
from functies
where prosecdef and not exists (select 1 from unnest(coalesce(proconfig, '{}')) c where c like 'search_path=%')
union all
select 'Geheime tabellen (tokens, geheimen) onleesbaar voor ingelogden',
       count(*) = 0,
       coalesce(string_agg(relname, ', '), '')
from tabellen
where relname in ('oauth_tokens', 'oauth_states', 'app_secrets')
  and has_table_privilege('authenticated', oid, 'select')
union all
select 'Tokens alleen versleuteld opgeslagen (geen leesbare tekst)',
       count(*) = 0,
       count(*)::text || ' tokens zonder versleuteling'
from public.oauth_tokens
-- AES-GCM-uitvoer is standaard base64; echte tokens bevatten punten, - of _ (ya29.…, eyJ….…)
where access_token_enc !~ '^[A-Za-z0-9+/]+=*$' or coalesce(refresh_token_enc, 'x') !~ '^[A-Za-z0-9+/]+=*$'
union all
select 'Profiel: alleen eigen gegevens wijzigbaar (kolomrechten)',
       not has_column_privilege('authenticated', 'public.profiles', 'id', 'update')
       and not has_column_privilege('authenticated', 'public.profiles', 'email', 'update')
       and not has_column_privilege('authenticated', 'public.profiles', 'created_at', 'update'),
       'id, email en created_at mogen niet door de app gewijzigd worden'
union all
select 'Statistiekencache alleen via de server (niet zelf te schrijven of te lezen)',
       not has_table_privilege('authenticated', 'public.metric_stats_cache', 'select,insert,update,delete'),
       'de app leest statistieken via metric_stats() en app_state()'
union all
select 'Wijzigingsmoment (data_changed_at) niet door de app aan te passen',
       not has_column_privilege('authenticated', 'public.profiles', 'data_changed_at', 'update'),
       'anders kan een gebruiker de cache omzeilen'
union all
select 'Controles op invoer profiel (lengte, gewicht, geboortedatum, weergave)',
       count(*) >= 5,
       count(*)::text || ' controles'
from pg_constraint
where conrelid = 'public.profiles'::regclass and contype = 'c'
union all
select 'Trainingen: elke policy alleen voor eigen rijen (select, insert, update, delete)',
       count(*) = 4 and bool_and(coalesce(qual, '') || coalesce(with_check, '') like '%auth.uid()%' and roles = '{authenticated}'),
       count(*)::text || ' policies'
from pg_policies
where schemaname = 'public' and tablename = 'workouts' and permissive = 'PERMISSIVE'
union all
select 'Controles op invoer trainingen (sport, bron, duur, tekstlengtes)',
       count(*) >= 5,
       count(*)::text || ' controles'
from pg_constraint
where conrelid = 'public.workouts'::regclass and contype = 'c'
union all
select 'Trainingsinstelling heeft vaste keuzes (auto, google_health, health_connect, all)',
       count(*) >= 1,
       count(*)::text || ' controle'
from pg_constraint
where conrelid = 'public.profiles'::regclass and contype = 'c' and pg_get_constraintdef(oid) like '%training_source%'
union all
select 'Wandelfilter heeft vaste keuzes (0, 10, 20, 30 minuten)',
       count(*) >= 1,
       count(*)::text || ' controle'
from pg_constraint
where conrelid = 'public.profiles'::regclass and contype = 'c' and pg_get_constraintdef(oid) like '%training_walk_min%'
union all
select 'Geen TRUNCATE, TRIGGER of REFERENCES voor app-rollen (TRUNCATE gaat langs row level security heen)',
       count(*) = 0,
       coalesce(string_agg(t.relname || ' (' || r.rolname || ')', ', '), '')
from tabellen t cross join (values ('anon'), ('authenticated')) r(rolname)
where has_table_privilege(r.rolname, t.oid, 'truncate') or has_table_privilege(r.rolname, t.oid, 'trigger')
   or has_table_privilege(r.rolname, t.oid, 'references')
union all
select 'Tweestapsverificatie afgedwongen: restrictive policy op elke tabel met gegevens van gebruikers',
       count(*) = 0,
       coalesce(string_agg(t.relname, ', '), '')
from tabellen t
-- Gebruikersgegevens: tabellen met user_id, plus profiles. metrics en providers zijn openbare lijsten.
where has_table_privilege('authenticated', t.oid, 'select,insert,update,delete')
  and (t.relname = 'profiles' or exists (select 1 from pg_attribute a where a.attrelid = t.oid and a.attname = 'user_id' and not a.attisdropped))
  and not exists (select 1 from pg_policies p where p.schemaname = 'public' and p.tablename = t.relname
                  and p.permissive = 'RESTRICTIVE' and p.qual like '%mfa_ok()%' and p.with_check like '%mfa_ok()%')
union all
select 'Functies die RLS omzeilen of de app openen controleren de tweede stap (app_state, ingest_health_connect, metric_stats, admin_overview)',
       count(*) = 4,
       coalesce(string_agg(proname, ', '), '')
from functies
where proname in ('app_state', 'ingest_health_connect', 'metric_stats', 'admin_overview')
  and pg_get_functiondef(oid) like '%mfa_ok()%'
union all
select 'Beheerderslijst niet leesbaar of aan te passen door de app',
       not has_table_privilege('authenticated', 'public.beheerders', 'select,insert,update,delete')
         and not has_table_privilege('anon', 'public.beheerders', 'select,insert,update,delete'),
       'alleen via is_admin() en admin_overview()'
union all
select 'Beheeroverzicht controleert beheerder en tweede stap',
       pg_get_functiondef('public.admin_overview()'::regprocedure) like '%is_admin()%mfa_ok()%',
       'anders ziet elke ingelogde alle gebruikers'
union all
select 'App-versie heeft een vaste vorm (x.y.z) en laatst gezien ligt niet ver in de toekomst',
       count(*) = 2,
       count(*)::text || ' controles'
from pg_constraint
where conrelid = 'public.profiles'::regclass and contype = 'c' and conname in ('profiles_app_version_check', 'profiles_app_seen_check')
union all
select 'Herstelcodes en pogingen niet leesbaar voor de app; codes alleen gehasht',
       not has_table_privilege('authenticated', 'public.mfa_herstelcodes', 'select,insert,update,delete')
         and not has_table_privilege('authenticated', 'public.mfa_herstel_pogingen', 'select,insert,update,delete')
         and not exists (select 1 from public.mfa_herstelcodes where code_hash !~ '^\$2[aby]\$'),
       'alleen via create_recovery_codes, use_recovery_code en recovery_codes_left'
union all
select 'Nieuwe herstelcodes alleen met tweede stap',
       pg_get_functiondef('public.create_recovery_codes()'::regprocedure) like '%aal2%',
       'anders maakt wie alleen je wachtwoord kent nieuwe codes';
