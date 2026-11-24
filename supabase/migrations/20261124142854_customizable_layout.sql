-- Alles aanpasbaar (v1.4.0): volgorde van de dagringen en de indeling van Vandaag, Gezondheid en Slaap.
alter table public.metric_prefs add column if not exists ring_position smallint check (ring_position between 1 and 3);
comment on column public.metric_prefs.ring_position is 'Plaats in de dagringen (1 = buitenste ring); alleen als in_rings';

-- Bestaande ringen krijgen een plaats in de volgorde van het dashboard
update public.metric_prefs m set ring_position = r.n
from (select user_id, metric, row_number() over (partition by user_id order by position, metric) as n
      from public.metric_prefs where in_rings) r
where m.user_id = r.user_id and m.metric = r.metric and m.ring_position is null;

alter table public.profiles add column if not exists layout jsonb not null default '{}'::jsonb
  check (jsonb_typeof(layout) = 'object' and pg_column_size(layout) < 8192);
comment on column public.profiles.layout is 'Indeling van de app: begroeting, Gezondheid (verborgen metrics) en Slaap (hersteltegels, advies)';

grant update (layout) on public.profiles to authenticated;
grant insert (ring_position), update (ring_position) on public.metric_prefs to authenticated;
