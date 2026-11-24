-- Garmin en Google schrijven bij elke sync de laatste dagen opnieuw weg (upsert), meestal met dezelfde waarde.
-- Een ongewijzigde rij niet bijwerken: geen schrijfwerk, geen WAL, en de statistiekencache blijft geldig.
create or replace function public.skip_unchanged_daily()
returns trigger language plpgsql set search_path = ''
as $$
begin
  if new.value is not distinct from old.value and new.provider is not distinct from old.provider then
    return null;
  end if;
  return new;
end $$;
revoke all on function public.skip_unchanged_daily() from public, anon, authenticated;

create trigger daily_metrics_skip_unchanged
  before update on public.daily_metrics
  for each row execute function public.skip_unchanged_daily();
