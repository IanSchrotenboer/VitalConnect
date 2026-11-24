-- Servertaak voor alle ophaal-bronnen (Google Health, Suunto) via de functie sync, in plaats van google-health-sync.
-- Elk kwartier, per portie (sync_shards in app_secrets); per bron wie 3 uur niet is bijgewerkt.
update public.app_secrets set name = 'sync_shards' where name = 'google_sync_shards';

select cron.unschedule('google-health-sync');
select cron.schedule('vitaal-sync', '*/15 * * * *', $job$
  select net.http_post(
    url := 'https://qtythirnpokfptchavgy.supabase.co/functions/v1/sync',
    headers := jsonb_build_object('Content-Type', 'application/json',
      'x-cron-secret', (select value from public.app_secrets where name = 'cron_secret')),
    body := jsonb_build_object('shard', s, 'shards', n.shards),
    timeout_milliseconds := 140000)
  from (select greatest(1, coalesce((select value::int from public.app_secrets where name = 'sync_shards'), 1)) as shards) n,
       generate_series(0, n.shards - 1) s
$job$);

-- Vervangen door provider_due(p_provider, ...)
drop function if exists public.google_health_due(integer, integer, integer, interval);
