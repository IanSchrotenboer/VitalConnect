revoke all on public.metrics, public.providers, public.profiles, public.connections, public.metric_prefs, public.daily_metrics from authenticated;
grant select on public.metrics, public.providers, public.connections to authenticated;
grant select, delete on public.daily_metrics to authenticated;
grant select, update on public.profiles to authenticated;
grant select, insert, update, delete on public.metric_prefs to authenticated;
