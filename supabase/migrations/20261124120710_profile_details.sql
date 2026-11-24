-- Persoonlijke gegevens en weergavekeuze in het profiel (v1.1.0)
alter table public.profiles
  add column if not exists birth_date date check (birth_date between date '1900-01-01' and current_date),
  add column if not exists sex text check (sex in ('female', 'male', 'other')),
  add column if not exists height_cm numeric(4,1) check (height_cm between 50 and 260),
  add column if not exists weight_kg numeric(4,1) check (weight_kg between 20 and 400),
  add column if not exists theme text not null default 'system' check (theme in ('system', 'light', 'dark'));

comment on column public.profiles.birth_date is 'Optioneel, door de gebruiker ingevuld';
comment on column public.profiles.sex is 'Optioneel: female, male of other';
comment on column public.profiles.height_cm is 'Lengte in cm, door de gebruiker ingevuld';
comment on column public.profiles.weight_kg is 'Gewicht in kg, door de gebruiker ingevuld';
comment on column public.profiles.theme is 'Weergave van de app: system, light of dark';

grant update (birth_date, sex, height_cm, weight_kg, theme) on public.profiles to authenticated;
