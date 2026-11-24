-- Tijdreeks van één metric voor de trendgrafiek, met dezelfde bronkeuze als resolved_daily.
create function public.resolved_series(p_metric text, p_from date, p_to date)
returns table (day date, value numeric, source text)
language sql stable security invoker set search_path = '' as $$
  select distinct on (d.day) d.day, d.value, d.source
  from public.daily_metrics d
  join public.providers p on p.id = d.provider
  left join public.metric_prefs mp on mp.user_id = d.user_id and mp.metric = d.metric
  where d.user_id = (select auth.uid())
    and d.metric = p_metric
    and d.day between p_from and p_to
  order by d.day,
    (mp.preferred_source is not null and (d.source = mp.preferred_source or d.provider = mp.preferred_source)) desc,
    p.priority,
    d.updated_at desc
$$;
revoke execute on function public.resolved_series(text, date, date) from public, anon;
grant execute on function public.resolved_series(text, date, date) to authenticated;
