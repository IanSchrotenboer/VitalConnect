-- Eén register van bronnen.
--   sync:      hoe de data binnenkomt: device (Health Connect op de telefoon), pull (de server haalt op), push (de bron stuurt zelf)
--   available: de koppeling staat open in de app (false = "binnenkort", bijv. zolang de bron ons nog geen toegang gaf)
--   priority:  bij twee bronnen voor dezelfde dag wint de laagste (de volgorde tussen bestaande bronnen blijft gelijk)
alter table public.providers
  add column if not exists sync text not null default 'pull' check (sync in ('device', 'pull', 'push')),
  add column if not exists available boolean not null default false;

update public.providers set priority = 4, sync = 'device', available = true where id = 'health_connect';
update public.providers set priority = 3, sync = 'pull',   available = true where id = 'google_health';
update public.providers set priority = 1, sync = 'push',   available = false where id = 'garmin';
insert into public.providers (id, name, priority, sync, available) values ('suunto', 'Suunto', 2, 'pull', false)
  on conflict (id) do nothing;

-- Welke gebruikers van een ophaal-bron (pull) zijn aan de beurt; vervangt google_health_due (die blijft tot de
-- oude functie google-health-sync uit Supabase is verwijderd).
create or replace function public.provider_due(p_provider text, p_shard integer, p_shards integer, p_limit integer, p_stale interval)
returns setof uuid language sql stable security definer set search_path = ''
as $$
  select c.user_id from public.connections c
  where c.provider = p_provider and c.status = 'active'
    and (c.last_sync_at is null or c.last_sync_at < now() - p_stale)
    and (p_shards <= 1 or abs(hashtext(c.user_id::text)) % p_shards = p_shard)
  order by c.last_sync_at nulls first
  limit least(p_limit, 500)
$$;
revoke all on function public.provider_due(text, integer, integer, integer, interval) from public, anon, authenticated;
grant execute on function public.provider_due(text, integer, integer, integer, interval) to service_role;

-- De app krijgt het bronnenregister mee bij het openen (welke koppelingen er zijn en welke al open staan).
create or replace function public.app_state(p_from date, p_to date, p_stats boolean default true)
returns jsonb language plpgsql set search_path = ''
as $$
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
    'stats', case when p_stats then (select coalesce(jsonb_agg(to_jsonb(s)), '[]') from public.metric_stats() s) end
  );
end $$;
