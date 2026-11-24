-- Statistieken over de hele geschiedenis per metric (tab Gezondheid), met dezelfde bronkeuze als resolved_daily.
create function public.metric_stats()
returns table (
  metric text, days bigint, first_day date, last_day date, last_value numeric,
  avg_all numeric, min_all numeric, max_all numeric, total_all numeric, avg_30 numeric
)
language sql stable security invoker set search_path = '' as $$
  with r as (
    select distinct on (d.metric, d.day) d.metric, d.day, d.value
    from public.daily_metrics d
    join public.providers p on p.id = d.provider
    left join public.metric_prefs mp on mp.user_id = d.user_id and mp.metric = d.metric
    where d.user_id = (select auth.uid())
    order by d.metric, d.day,
      (mp.preferred_source is not null and (d.source = mp.preferred_source or d.provider = mp.preferred_source)) desc,
      p.priority,
      d.updated_at desc
  )
  select r.metric, count(*), min(r.day), max(r.day),
         (array_agg(r.value order by r.day desc))[1],
         round(avg(r.value), 2), min(r.value), max(r.value), sum(r.value),
         round(avg(r.value) filter (where r.day > current_date - 30), 2)
  from r
  group by r.metric
$$;
revoke execute on function public.metric_stats() from public, anon;
grant execute on function public.metric_stats() to authenticated;
