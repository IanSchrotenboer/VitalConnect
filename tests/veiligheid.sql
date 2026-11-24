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
select 'Controles op invoer profiel (lengte, gewicht, geboortedatum, weergave)',
       count(*) >= 5,
       count(*)::text || ' controles'
from pg_constraint
where conrelid = 'public.profiles'::regclass and contype = 'c';
