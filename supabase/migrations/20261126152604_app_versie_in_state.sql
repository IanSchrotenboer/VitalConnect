-- app_state geeft de gemelde app-versie terug; de app schrijft hem alleen bij een nieuwe versie of een nieuwe dag.
do $$
declare d text; oud text := '''training_walk_light'', p.training_walk_light)';
  nieuw text := '''training_walk_light'', p.training_walk_light, ''app_version'', p.app_version, ''app_seen_at'', p.app_seen_at)';
begin
  select pg_get_functiondef('public.app_state(date,date,boolean)'::regprocedure) into d;
  if position(nieuw in d) > 0 then return; end if;
  if position(oud in d) = 0 then raise exception 'tekst niet gevonden in app_state'; end if;
  execute replace(d, oud, nieuw);
end $$;
