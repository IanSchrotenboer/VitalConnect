-- Herstelcodes voor tweestapsverificatie: de gebruiker is zelf verantwoordelijk voor zijn authenticator-app.
-- Bij het aanzetten krijgt hij 10 eenmalige codes. Telefoon kwijt: inloggen (wachtwoord, Google of code per mail),
-- een herstelcode invullen, en tweestapsverificatie staat uit. Codes staan alleen gehasht (bcrypt) in de database.
create table if not exists public.mfa_herstelcodes (
  user_id uuid not null references auth.users (id) on delete cascade,
  code_hash text not null,
  gemaakt timestamptz not null default now(),
  primary key (user_id, code_hash)
);
alter table public.mfa_herstelcodes enable row level security;
revoke all on public.mfa_herstelcodes from anon, authenticated;

-- Mislukte pogingen, om raden te voorkomen (5 per uur per gebruiker)
create table if not exists public.mfa_herstel_pogingen (
  user_id uuid not null references auth.users (id) on delete cascade,
  op timestamptz not null default now()
);
create index if not exists mfa_herstel_pogingen_user on public.mfa_herstel_pogingen (user_id, op);
alter table public.mfa_herstel_pogingen enable row level security;
revoke all on public.mfa_herstel_pogingen from anon, authenticated;

-- Nieuwe set van 10 codes (vervangt de oude). Alleen met tweede stap (aal2) en een bevestigde authenticator-app,
-- zodat iemand met alleen je wachtwoord geen nieuwe codes kan maken. Geeft de codes één keer terug.
create or replace function public.create_recovery_codes()
returns text[] language plpgsql volatile security definer set search_path = '' as $$
declare
  v_uid uuid := auth.uid();
  abc constant text := 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  codes text[] := '{}';
  c text; b bytea;
begin
  if v_uid is null then raise exception 'Niet ingelogd'; end if;
  if coalesce(auth.jwt() ->> 'aal', 'aal1') <> 'aal2'
     or not exists (select 1 from auth.mfa_factors f where f.user_id = v_uid and f.status = 'verified') then
    raise exception 'Tweede stap nodig' using errcode = '42501', hint = 'mfa_required';
  end if;
  delete from public.mfa_herstelcodes where user_id = v_uid;
  for i in 1..10 loop
    b := extensions.gen_random_bytes(10);
    c := '';
    for j in 0..9 loop c := c || substr(abc, 1 + get_byte(b, j) % 32, 1); end loop;
    c := substr(c, 1, 5) || '-' || substr(c, 6, 5);
    codes := codes || c;
    insert into public.mfa_herstelcodes (user_id, code_hash) values (v_uid, extensions.crypt(replace(c, '-', ''), extensions.gen_salt('bf', 8)));
  end loop;
  return codes;
end $$;

-- Herstelcode gebruiken: werkt met een gewone sessie (aal1). Juist = code vervalt en tweestapsverificatie gaat uit.
create or replace function public.use_recovery_code(p_code text)
returns boolean language plpgsql volatile security definer set search_path = '' as $$
declare
  v_uid uuid := auth.uid();
  v_code text := upper(regexp_replace(coalesce(p_code, ''), '[^A-Za-z0-9]', '', 'g'));
  v_hash text;
begin
  if v_uid is null then raise exception 'Niet ingelogd'; end if;
  delete from public.mfa_herstel_pogingen where user_id = v_uid and op < now() - interval '1 hour';
  if (select count(*) from public.mfa_herstel_pogingen where user_id = v_uid) >= 5 then
    raise exception 'Te veel pogingen. Probeer het over een uur opnieuw.' using errcode = '54000';
  end if;
  if length(v_code) = 10 then
    select h.code_hash into v_hash from public.mfa_herstelcodes h
      where h.user_id = v_uid and h.code_hash = extensions.crypt(v_code, h.code_hash) limit 1;
  end if;
  if v_hash is null then
    insert into public.mfa_herstel_pogingen (user_id) values (v_uid);
    return false;
  end if;
  delete from public.mfa_herstelcodes where user_id = v_uid;            -- alle codes vervallen; bij opnieuw aanzetten komen er nieuwe
  delete from public.mfa_herstel_pogingen where user_id = v_uid;
  delete from auth.mfa_factors where user_id = v_uid;                   -- tweestapsverificatie uit
  return true;
end $$;

revoke all on function public.create_recovery_codes() from public, anon;
revoke all on function public.use_recovery_code(text) from public, anon;
grant execute on function public.create_recovery_codes() to authenticated;
grant execute on function public.use_recovery_code(text) to authenticated;

-- Hoeveel herstelcodes er nog zijn (voor Profiel → Tweestapsverificatie); de tabel zelf is niet leesbaar voor de app
create or replace function public.recovery_codes_left()
returns integer language sql stable security definer set search_path = '' as $$
  select count(*)::int from public.mfa_herstelcodes h where h.user_id = auth.uid();
$$;
revoke all on function public.recovery_codes_left() from public, anon;
grant execute on function public.recovery_codes_left() to authenticated;

-- app_state geeft het aantal mee (geen extra verzoek bij openen)
do $$
declare d text; oud text := '''is_admin'', public.is_admin(),';
  nieuw text := '''is_admin'', public.is_admin(),
    ''recovery_codes'', public.recovery_codes_left(),';
begin
  select pg_get_functiondef('public.app_state(date,date,boolean)'::regprocedure) into d;
  if position(nieuw in d) > 0 then return; end if;
  if position(oud in d) = 0 then raise exception 'tekst niet gevonden in app_state'; end if;
  execute replace(d, oud, nieuw);
end $$;
