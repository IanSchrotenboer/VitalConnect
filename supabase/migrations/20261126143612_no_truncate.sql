-- Ingelogden mogen alleen lezen, toevoegen, wijzigen en verwijderen (met row level security).
-- TRUNCATE gaat langs row level security heen: daarmee kon elke ingelogde gebruiker de trainingen van iedereen wissen.
-- REFERENCES en TRIGGER zijn ook niet nodig. Zelfde keuze als in BOA Paraat (voortgang_rechten_strakker).
revoke truncate, references, trigger on all tables in schema public from anon, authenticated;

-- Ook voor tabellen die later bijkomen (Supabase geeft nieuwe tabellen standaard alle rechten)
alter default privileges for role postgres in schema public revoke truncate, references, trigger on tables from anon, authenticated;
