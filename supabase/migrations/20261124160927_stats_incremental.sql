-- Statistieken incrementeel (v1.7.0): de cache bevat alleen de dagen vóór vandaag; vandaag wordt er bij het
-- lezen bij opgeteld. Uploads van vandaag (elk kwartier tot elk uur) maken de cache dus niet meer ongeldig;
-- alleen wijzigingen in eerdere dagen of in de voorkeursbron doen dat.

create or replace function public.mark_data_changed_new() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  update public.profiles p set data_changed_at = clock_timestamp()
  where p.id in (select distinct n.user_id from new_rows n where n.day < current_date);
  return null;
end $$;

create or replace function public.mark_data_changed_old() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  update public.profiles p set data_changed_at = clock_timestamp()
  where p.id in (select distinct o.user_id from old_rows o where o.day < current_date);
  return null;
end $$;

delete from public.metric_stats_cache;  -- nieuw formaat

create or replace function public.metric_stats()
returns table (metric text, days bigint, first_day date, last_day date, last_value numeric,
               avg_all numeric, min_all numeric, max_all numeric, total_all numeric, avg_30 numeric)
language plpgsql security definer set search_path = '' as $$
declare
  v_uid uuid := auth.uid();
  v_cache public.metric_stats_cache;
  v_changed timestamptz;
  v_hist jsonb;
begin
  if v_uid is null then return; end if;
  select * into v_cache from public.metric_stats_cache c where c.user_id = v_uid;
  select p.data_changed_at into v_changed from public.profiles p where p.id = v_uid;
  if v_cache.user_id is not null and v_cache.computed_at >= coalesce(v_changed, '-infinity')
     and v_cache.computed_at::date = current_date then
    v_hist := v_cache.stats;
  else
    -- Geschiedenis tot en met gisteren, per metric: aantallen en sommen, zodat vandaag erbij opgeteld kan worden
    with r as (
      select distinct on (d.metric, d.day) d.metric, d.day, d.value
      from public.daily_metrics d
      join public.providers pr on pr.id = d.provider
      left join public.metric_prefs mp on mp.user_id = d.user_id and mp.metric = d.metric
      where d.user_id = v_uid and d.day < current_date
      order by d.metric, d.day,
        (mp.preferred_source is not null and (d.source = mp.preferred_source or d.provider = mp.preferred_source)) desc,
        pr.priority, d.updated_at desc
    ), s as (
      select r.metric, count(*) as n, min(r.day) as first_day, max(r.day) as last_day,
             (array_agg(r.value order by r.day desc))[1] as last_value,
             sum(r.value) as total, min(r.value) as lo, max(r.value) as hi,
             count(*) filter (where r.day > current_date - 30) as n30,
             coalesce(sum(r.value) filter (where r.day > current_date - 30), 0) as sum30
      from r group by r.metric
    )
    select coalesce(jsonb_agg(to_jsonb(s)), '[]'::jsonb) into v_hist from s;
    insert into public.metric_stats_cache (user_id, stats, computed_at) values (v_uid, v_hist, clock_timestamp())
    on conflict (user_id) do update set stats = excluded.stats, computed_at = excluded.computed_at;
  end if;

  return query
    with h as (
      select * from jsonb_to_recordset(v_hist) as x(metric text, n bigint, first_day date, last_day date, last_value numeric,
                                                    total numeric, lo numeric, hi numeric, n30 bigint, sum30 numeric)
    ), t as (
      select d.metric, d.value from public.resolved_daily(current_date, current_date) d
    )
    select coalesce(h.metric, t.metric),
           coalesce(h.n, 0) + (t.value is not null)::int,
           coalesce(h.first_day, current_date),
           case when t.value is not null then current_date else h.last_day end,
           coalesce(t.value, h.last_value),
           round((coalesce(h.total, 0) + coalesce(t.value, 0)) / nullif(coalesce(h.n, 0) + (t.value is not null)::int, 0), 2),
           least(h.lo, t.value), greatest(h.hi, t.value),
           coalesce(h.total, 0) + coalesce(t.value, 0),
           round((coalesce(h.sum30, 0) + coalesce(t.value, 0)) / nullif(coalesce(h.n30, 0) + (t.value is not null)::int, 0), 2)
    from h full join t on t.metric = h.metric;
end $$;
revoke all on function public.metric_stats() from public, anon;
grant execute on function public.metric_stats() to authenticated;
