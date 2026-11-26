-- 1) Tweestapsverificatie (vrijwillig, authenticator-app), zoals in BOA Paraat.
-- Wie het aanzet, kan zijn gegevens alleen nog lezen en bewaren met een sessie waarin de tweede stap is gedaan (aal2).
-- Wie het niet aanzet, merkt niets. auth.mfa_factors is niet leesbaar voor ingelogden, daarom security definer.
create or replace function public.mfa_ok()
returns boolean language sql stable security definer set search_path = '' as $$
  select coalesce(auth.jwt() ->> 'aal', 'aal1') = 'aal2'
      or not exists (select 1 from auth.mfa_factors f where f.user_id = auth.uid() and f.status = 'verified');
$$;
revoke all on function public.mfa_ok() from public, anon;
grant execute on function public.mfa_ok() to authenticated;

-- Restrictive: geldt bovenop de bestaande policies (eigen rijen), voor alles wat een ingelogde doet
do $$
declare t text;
begin
  foreach t in array array['profiles', 'metric_prefs', 'daily_metrics', 'connections', 'sync_runs', 'workouts'] loop
    execute format('drop policy if exists "tweestapsverificatie" on public.%I', t);
    execute format('create policy "tweestapsverificatie" on public.%I as restrictive for all to authenticated
                    using ((select public.mfa_ok())) with check ((select public.mfa_ok()))', t);
  end loop;
end $$;

-- 2) Beheer: wie de app gebruikt, met welke versie, welke koppelingen en welke fouten (geen gezondheidswaarden).
alter table public.profiles add column if not exists app_version text,
  add column if not exists app_seen_at timestamptz;
alter table public.profiles drop constraint if exists profiles_app_version_check;
alter table public.profiles add constraint profiles_app_version_check
  check (app_version is null or app_version ~ '^[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}$');
alter table public.profiles drop constraint if exists profiles_app_seen_check;
alter table public.profiles add constraint profiles_app_seen_check check (app_seen_at is null or app_seen_at <= now() + interval '1 day');
grant update (app_version, app_seen_at) on public.profiles to authenticated;

create table if not exists public.beheerders (
  user_id uuid primary key references auth.users (id) on delete cascade,
  toegevoegd timestamptz not null default now()
);
comment on table public.beheerders is 'Wie het beheeroverzicht mag zien. Toevoegen: insert into public.beheerders (user_id) select id from auth.users where email = ''…'';';
alter table public.beheerders enable row level security;
revoke all on public.beheerders from anon, authenticated;

create or replace function public.is_admin()
returns boolean language sql stable security definer set search_path = '' as $$
  select exists (select 1 from public.beheerders b where b.user_id = auth.uid());
$$;
revoke all on function public.is_admin() from public, anon;
grant execute on function public.is_admin() to authenticated;

create or replace function public.admin_overview()
returns jsonb language plpgsql stable security definer set search_path = '' as $$
declare
  vandaag date := (now() at time zone 'Europe/Amsterdam')::date;
begin
  if not public.is_admin() or not public.mfa_ok() then
    raise exception 'Alleen voor beheerders' using errcode = '42501';
  end if;
  return jsonb_build_object(
    'nu', now(),
    'kpi', jsonb_build_object(
      'gebruikers', (select count(*) from auth.users),
      'actief_vandaag', (select count(*) from auth.users u left join public.profiles p on p.id = u.id
                         where (greatest(u.last_sign_in_at, p.app_seen_at, p.data_changed_at) at time zone 'Europe/Amsterdam')::date = vandaag),
      'actief_7d', (select count(*) from auth.users u left join public.profiles p on p.id = u.id
                    where greatest(u.last_sign_in_at, p.app_seen_at, p.data_changed_at) > now() - interval '7 days'),
      'nieuw_7d', (select count(*) from auth.users where created_at > now() - interval '7 days'),
      'tweestaps', (select count(distinct user_id) from auth.mfa_factors where status = 'verified'),
      'fouten_7d', (select count(*) from public.sync_runs where started_at > now() - interval '7 days')),
    'versies', (select coalesce(jsonb_agg(jsonb_build_object('versie', v, 'aantal', n) order by v desc nulls last), '[]')
                from (select p.app_version v, count(*) n from public.profiles p group by 1) x),
    'koppelingen', (select coalesce(jsonb_agg(jsonb_build_object('bron', provider, 'status', status, 'aantal', n) order by provider, status), '[]')
                    from (select provider, status, count(*) n from public.connections group by 1, 2) x),
    'fouten', (select coalesce(jsonb_agg(jsonb_build_object('bron', provider, 'aantal', n, 'laatste', laatste, 'melding', melding) order by laatste desc), '[]')
               from (select provider, count(*) n, max(started_at) laatste,
                            (array_agg(left(coalesce(error, ''), 160) order by started_at desc))[1] melding
                     from public.sync_runs where started_at > now() - interval '7 days' group by 1) x),
    'gebruikers', (select coalesce(jsonb_agg(g order by g->>'actief' desc nulls last), '[]') from (
      select jsonb_build_object(
        'email', u.email, 'naam', p.display_name, 'sinds', u.created_at,
        'actief', greatest(u.last_sign_in_at, p.app_seen_at, p.data_changed_at),
        'versie', p.app_version,
        'tweestaps', exists (select 1 from auth.mfa_factors f where f.user_id = u.id and f.status = 'verified'),
        'koppelingen', (select coalesce(jsonb_agg(jsonb_build_object('bron', c.provider, 'status', c.status, 'laatst', c.last_sync_at)), '[]')
                        from public.connections c where c.user_id = u.id),
        'fouten_7d', (select count(*) from public.sync_runs r where r.user_id = u.id and r.started_at > now() - interval '7 days')) g
      from auth.users u left join public.profiles p on p.id = u.id
      order by greatest(u.last_sign_in_at, p.app_seen_at, p.data_changed_at) desc nulls last limit 500) x)
  );
end $$;
revoke all on function public.admin_overview() from public, anon;
grant execute on function public.admin_overview() to authenticated;

-- 3) Bestaande functies: een duidelijke fout zonder tweede stap, en app_state geeft door of je beheerder bent.
-- Precieze tekstvervanging in de huidige definitie (faalt hard als de tekst niet meer klopt).
do $$
declare
  f record; d text; n text;
begin
  for f in select * from (values
    ('app_state', 'if v_uid is null then raise exception ''Niet ingelogd''; end if;',
     'if v_uid is null then raise exception ''Niet ingelogd''; end if;
  if not public.mfa_ok() then raise exception ''Tweede stap nodig'' using errcode = ''42501'', hint = ''mfa_required''; end if;'),
    ('app_state', 'return jsonb_build_object(
    ''metrics'',',
     'return jsonb_build_object(
    ''is_admin'', public.is_admin(),
    ''metrics'','),
    ('ingest_health_connect', '    raise exception ''Niet ingelogd'';
  end if;',
     '    raise exception ''Niet ingelogd'';
  end if;
  if not public.mfa_ok() then raise exception ''Tweede stap nodig'' using errcode = ''42501'', hint = ''mfa_required''; end if;'),
    ('metric_stats', 'if v_uid is null then return; end if;',
     'if v_uid is null or not public.mfa_ok() then return; end if;')
  ) v(fn, oud, nieuw) loop
    select pg_get_functiondef(p.oid) into d from pg_proc p join pg_namespace s on s.oid = p.pronamespace
      where s.nspname = 'public' and p.proname = f.fn;
    if position(f.nieuw in d) > 0 then continue; end if;          -- al toegepast
    if position(f.oud in d) = 0 then raise exception 'tekst niet gevonden in %', f.fn; end if;
    n := replace(d, f.oud, f.nieuw);
    execute n;
  end loop;
end $$;
