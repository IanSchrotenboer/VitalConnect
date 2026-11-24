-- Kennismaking na de eerste keer inloggen: gegevens importeren en profiel aanvullen (v1.2.0)
alter table public.profiles add column if not exists onboarded_at timestamptz;
comment on column public.profiles.onboarded_at is 'Moment waarop de gebruiker de kennismaking (importeren, profiel) heeft afgerond of overgeslagen';
-- Wie al toestemming gaf, heeft de app al ingericht: geen kennismaking meer tonen.
update public.profiles set onboarded_at = consent_health_at where onboarded_at is null and consent_health_at is not null;
grant update (onboarded_at) on public.profiles to authenticated;
