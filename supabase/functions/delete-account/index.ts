// POST /delete-account  (JWT)
// AVG: trekt de toestemming in bij elke gekoppelde bron en verwijdert daarna het account met alle data (cascade).
import { revokeAtProvider } from "../_shared/oauth.ts";
import { PROVIDERS } from "../_shared/providers/index.ts";
import { authed, json } from "../_shared/shared.ts";

Deno.serve(authed(async (_req, sb, user) => {
  const revoked: Record<string, string> = {};
  for (const p of Object.values(PROVIDERS)) revoked[p.id] = await revokeAtProvider(sb, user.id, p);

  const { error } = await sb.auth.admin.deleteUser(user.id);
  if (error) throw error;
  return json({ deleted: true, revoked });
}));
