-- Robuuste upload (v1.7.0, uit de chaostest): één kapotte regel (tekst als waarde, onmogelijke datum, geen object)
-- liet de hele upload mislukken. Nu worden alleen de geldige regels opgeslagen.
create or replace function public.try_date(p text) returns date
language plpgsql immutable set search_path = '' as $$
begin
  if p is null or p !~ '^\d{4}-\d{2}-\d{2}$' then return null; end if;
  return p::date;
exception when others then return null;
end $$;
revoke all on function public.try_date(text) from public, anon, authenticated;

create or replace function public.ingest_health_connect(p_rows jsonb)
returns integer language plpgsql security definer set search_path = '' as $$
declare
  v_uid uuid := auth.uid();
  v_count integer;
begin
  if v_uid is null then
    raise exception 'Niet ingelogd';
  end if;
  if jsonb_typeof(p_rows) <> 'array' or jsonb_array_length(p_rows) > 10000 then
    raise exception 'Ongeldige payload';
  end if;

  with r as (
    select public.try_date(e->>'day') as day, e->>'metric' as metric,
           e->>'origin' as origin, (e->>'value')::numeric as value
    from jsonb_array_elements(p_rows) e
    where jsonb_typeof(e) = 'object' and jsonb_typeof(e->'value') = 'number'
  ), ok as (
    select distinct on (r.day, r.metric, r.origin) r.*
    from r join public.metrics m on m.id = r.metric
    where r.day between current_date - 800 and current_date + 1
      and r.origin ~ '^[A-Za-z0-9_.]{1,200}$'
      and r.value between m.min_value and m.max_value
  )
  insert into public.daily_metrics (user_id, day, metric, source, provider, value, updated_at)
  select v_uid, day, metric, 'health_connect:' || origin, 'health_connect', value, now() from ok
  on conflict (user_id, day, metric, source)
  do update set value = excluded.value, updated_at = now()
  where public.daily_metrics.value is distinct from excluded.value;
  get diagnostics v_count = row_count;

  insert into public.connections (user_id, provider, status, last_data_at, last_sync_at, last_error)
  values (v_uid, 'health_connect', 'active', now(), now(), null)
  on conflict (user_id, provider)
  do update set status = 'active', last_sync_at = now(), last_error = null,
                last_data_at = case when v_count > 0 then now() else public.connections.last_data_at end;
  return v_count;
end $$;
