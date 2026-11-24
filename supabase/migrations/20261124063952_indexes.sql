-- Indexen voor verwijderen per gebruiker (account wissen) en per bron (ontkoppelen met data wissen).
create index if not exists oauth_states_user on public.oauth_states (user_id);
create index if not exists provider_raw_user on public.provider_raw (user_id);
create index if not exists daily_metrics_user_provider on public.daily_metrics (user_id, provider);
create index if not exists sync_runs_provider on public.sync_runs (provider);
