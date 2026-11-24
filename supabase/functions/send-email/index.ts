// Supabase Auth "Send Email Hook": alle account-mails in het Nederlands, via Resend.
// Activeer in het dashboard: Authentication → Hooks → Send Email → HTTPS → deze functie.
// Secrets: SEND_EMAIL_HOOK_SECRET (uit het hook-scherm), RESEND_API_KEY, EMAIL_FROM.
import { Webhook } from "npm:standardwebhooks@1.0.0";
import { env } from "../_shared/shared.ts";

type EmailData = {
  token?: string;
  token_hash?: string;
  token_new?: string;
  token_hash_new?: string;
  redirect_to?: string;
  email_action_type: string;
  site_url?: string;
  old_email?: string;
};
type User = { email: string; new_email?: string; user_metadata?: Record<string, unknown> };

const hookError = (status: number, message: string) =>
  new Response(JSON.stringify({ error: { http_code: status, message } }), {
    status, headers: { "Content-Type": "application/json" },
  });

const esc = (s: string) => s.replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]!));

// ---------- Teksten ----------
type Copy = { subject: string; intro: string; action?: string; codeLabel?: string };

const COPY: Record<string, Copy> = {
  signup: {
    subject: "Bevestig je e-mailadres voor Vitaal",
    intro: "Welkom bij Vitaal. Vul deze code in de app in om je e-mailadres te bevestigen.",
    action: "E-mailadres bevestigen", codeLabel: "Je bevestigingscode",
  },
  magiclink: {
    subject: "Je inlogcode voor Vitaal",
    intro: "Gebruik deze code om in te loggen bij Vitaal. De code is één uur geldig.",
    action: "Inloggen", codeLabel: "Je inlogcode",
  },
  email: {
    subject: "Je inlogcode voor Vitaal",
    intro: "Gebruik deze code om in te loggen bij Vitaal. De code is één uur geldig.",
    action: "Inloggen", codeLabel: "Je inlogcode",
  },
  recovery: {
    subject: "Nieuw wachtwoord instellen voor Vitaal",
    intro: "Je hebt gevraagd om een nieuw wachtwoord. Vul deze code in de app in en kies daarna een nieuw wachtwoord.",
    action: "Nieuw wachtwoord instellen", codeLabel: "Je herstelcode",
  },
  invite: {
    subject: "Je bent uitgenodigd voor Vitaal",
    intro: "Je bent uitgenodigd om Vitaal te gebruiken. Vul deze code in de app in om je account te activeren.",
    action: "Uitnodiging accepteren", codeLabel: "Je uitnodigingscode",
  },
  email_change: {
    subject: "Bevestig de wijziging van je e-mailadres",
    intro: "Je wilt het e-mailadres van je Vitaal-account wijzigen. Bevestig dit met de code hieronder.",
    action: "Wijziging bevestigen", codeLabel: "Je bevestigingscode",
  },
  reauthentication: {
    subject: "Je bevestigingscode voor Vitaal",
    intro: "Bevestig met deze code dat jij het bent.",
    codeLabel: "Je code",
  },
  password_changed_notification: {
    subject: "Je Vitaal-wachtwoord is gewijzigd",
    intro: "Het wachtwoord van je Vitaal-account is zojuist gewijzigd.",
  },
  email_changed_notification: {
    subject: "Het e-mailadres van je Vitaal-account is gewijzigd",
    intro: "Het e-mailadres van je Vitaal-account is gewijzigd.",
  },
  identity_linked_notification: {
    subject: "Nieuwe inlogmethode gekoppeld aan Vitaal",
    intro: "Er is een nieuwe inlogmethode (bijvoorbeeld Google) aan je Vitaal-account gekoppeld.",
  },
  identity_unlinked_notification: {
    subject: "Inlogmethode ontkoppeld van Vitaal",
    intro: "Er is een inlogmethode van je Vitaal-account ontkoppeld.",
  },
  mfa_factor_enrolled_notification: {
    subject: "Tweestapsverificatie ingesteld",
    intro: "Er is een extra verificatiestap aan je Vitaal-account toegevoegd.",
  },
  mfa_factor_unenrolled_notification: {
    subject: "Tweestapsverificatie verwijderd",
    intro: "Er is een verificatiestap van je Vitaal-account verwijderd.",
  },
};

const SECURITY_NOTE = "Was jij dit niet? Wijzig dan meteen je wachtwoord in de app, of neem contact met ons op.";
const IGNORE_NOTE = "Heb je dit niet aangevraagd? Dan kun je deze e-mail negeren.";

function verifyLink(tokenHash: string, type: string, redirectTo?: string): string {
  const q = new URLSearchParams({ token: tokenHash, type });
  if (redirectTo) q.set("redirect_to", redirectTo);
  return `${env("SUPABASE_URL")}/auth/v1/verify?${q}`;
}

function render(c: Copy, code?: string, link?: string, notice = false) {
  const note = notice ? SECURITY_NOTE : IGNORE_NOTE;
  const html = `<!DOCTYPE html><html lang="nl"><body style="margin:0;background:#f3f3f0;font-family:Inter,Segoe UI,Roboto,Arial,sans-serif;color:#121316">
<table role="presentation" width="100%" cellpadding="0" cellspacing="0"><tr><td align="center" style="padding:32px 16px">
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="max-width:520px;background:#ffffff;border-radius:18px">
<tr><td style="background:#121318;border-radius:18px 18px 0 0;padding:22px 28px;color:#f4f4f1;font-size:20px;font-weight:700">Vitaal</td></tr>
<tr><td style="padding:28px">
<p style="margin:0 0 20px;font-size:16px;line-height:1.55">${esc(c.intro)}</p>
${code ? `<p style="margin:0 0 6px;font-size:14px;color:#5b5e66">${esc(c.codeLabel ?? "Je code")}</p>
<p style="margin:0 0 24px;font-size:34px;font-weight:700;letter-spacing:6px;color:#e8492a">${esc(code)}</p>` : ""}
${link && c.action ? `<p style="margin:0 0 24px"><a href="${esc(link)}" style="display:inline-block;background:#121316;color:#ffffff;text-decoration:none;font-weight:600;padding:12px 20px;border-radius:22px">${esc(c.action)}</a></p>` : ""}
<p style="margin:0;font-size:14px;line-height:1.5;color:#5b5e66">${esc(note)}</p>
</td></tr></table>
<p style="margin:16px 0 0;font-size:13px;color:#5b5e66">Vitaal · je gezondheidsdata op één plek</p>
</td></tr></table></body></html>`;
  const text = [c.intro, code ? `${c.codeLabel ?? "Je code"}: ${code}` : "", link && c.action ? `${c.action}: ${link}` : "", note]
    .filter(Boolean).join("\n\n");
  return { html, text };
}

async function send(to: string, subject: string, html: string, text: string) {
  const r = await fetch("https://api.resend.com/emails", {
    method: "POST",
    headers: { Authorization: `Bearer ${env("RESEND_API_KEY")}`, "Content-Type": "application/json" },
    body: JSON.stringify({ from: env("EMAIL_FROM"), to: [to], subject, html, text }),
  });
  if (!r.ok) throw new Error(`Resend ${r.status}: ${await r.text()}`);
}

Deno.serve(async (req) => {
  if (req.method !== "POST") return hookError(405, "method");
  const payload = await req.text();
  let user: User, data: EmailData;
  try {
    const wh = new Webhook(env("SEND_EMAIL_HOOK_SECRET").replace("v1,whsec_", ""));
    ({ user, email_data: data } = wh.verify(payload, Object.fromEntries(req.headers)) as { user: User; email_data: EmailData });
  } catch (e) {
    console.error("verify", e);
    return hookError(401, "Ongeldige handtekening of ontbrekende configuratie");
  }

  try {
    const type = data.email_action_type;
    const c = COPY[type] ?? { subject: "Bericht van Vitaal", intro: "Er is een wijziging in je Vitaal-account." };
    const isNotice = type.endsWith("_notification");
    // Links alleen als er een echte site-URL is; de app werkt met de code
    const site = data.redirect_to || data.site_url || "";
    const withLink = !!site && !/localhost|127\.0\.0\.1/.test(site);

    if (type === "email_change" && user.new_email && data.token_new) {
      // Veilige wijziging: twee mails. Let op: de _new-velden zijn omgedraaid (zie Supabase-docs).
      const cur = render(c, data.token, withLink && data.token_hash_new ? verifyLink(data.token_hash_new, type, data.redirect_to) : undefined);
      const nxt = render(c, data.token_new, withLink && data.token_hash ? verifyLink(data.token_hash, type, data.redirect_to) : undefined);
      await send(user.email, c.subject, cur.html, cur.text);
      await send(user.new_email, c.subject, nxt.html, nxt.text);
    } else {
      const to = type === "email_change" && user.new_email ? user.new_email : user.email;
      const code = isNotice ? undefined : (data.token || data.token_new);
      const hash = data.token_hash || data.token_hash_new;
      const link = !isNotice && withLink && hash ? verifyLink(hash, type, data.redirect_to) : undefined;
      const m = render(c, code, link, isNotice);
      await send(to, c.subject, m.html, m.text);
    }
    return new Response("{}", { status: 200, headers: { "Content-Type": "application/json" } });
  } catch (e) {
    console.error("send", e);
    return hookError(500, "E-mail versturen mislukt");
  }
});
