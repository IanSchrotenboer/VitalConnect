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
type Copy = { subject: string; intro: string; codeLabel?: string };

const COPY: Record<string, Copy> = {
  signup: {
    subject: "Bevestig je e-mailadres voor Vital Connect",
    intro: "Welkom bij Vital Connect. Vul deze code in de app in om je e-mailadres te bevestigen.",
    codeLabel: "Je bevestigingscode",
  },
  magiclink: {
    subject: "Je inlogcode voor Vital Connect",
    intro: "Gebruik deze code om in te loggen bij Vital Connect. De code is één uur geldig.",
    codeLabel: "Je inlogcode",
  },
  email: {
    subject: "Je inlogcode voor Vital Connect",
    intro: "Gebruik deze code om in te loggen bij Vital Connect. De code is één uur geldig.",
    codeLabel: "Je inlogcode",
  },
  recovery: {
    subject: "Nieuw wachtwoord instellen voor Vital Connect",
    intro: "Je hebt gevraagd om een nieuw wachtwoord. Vul deze code in de app in en kies daarna een nieuw wachtwoord.",
    codeLabel: "Je herstelcode",
  },
  invite: {
    subject: "Je bent uitgenodigd voor Vital Connect",
    intro: "Je bent uitgenodigd om Vital Connect te gebruiken. Vul deze code in de app in om je account te activeren.",
    codeLabel: "Je uitnodigingscode",
  },
  email_change: {
    subject: "Bevestig de wijziging van je e-mailadres",
    intro: "Je wilt het e-mailadres van je Vital Connect-account wijzigen. Bevestig dit met de code hieronder.",
    codeLabel: "Je bevestigingscode",
  },
  reauthentication: {
    subject: "Je bevestigingscode voor Vital Connect",
    intro: "Bevestig met deze code dat jij het bent.",
    codeLabel: "Je code",
  },
  password_changed_notification: {
    subject: "Je Vital Connect-wachtwoord is gewijzigd",
    intro: "Het wachtwoord van je Vital Connect-account is zojuist gewijzigd.",
  },
  email_changed_notification: {
    subject: "Het e-mailadres van je Vital Connect-account is gewijzigd",
    intro: "Het e-mailadres van je Vital Connect-account is gewijzigd.",
  },
  identity_linked_notification: {
    subject: "Nieuwe inlogmethode gekoppeld aan Vital Connect",
    intro: "Er is een nieuwe inlogmethode (bijvoorbeeld Google) aan je Vital Connect-account gekoppeld.",
  },
  identity_unlinked_notification: {
    subject: "Inlogmethode ontkoppeld van Vital Connect",
    intro: "Er is een inlogmethode van je Vital Connect-account ontkoppeld.",
  },
  mfa_factor_enrolled_notification: {
    subject: "Tweestapsverificatie ingesteld",
    intro: "Er is een extra verificatiestap aan je Vital Connect-account toegevoegd.",
  },
  mfa_factor_unenrolled_notification: {
    subject: "Tweestapsverificatie verwijderd",
    intro: "Er is een verificatiestap van je Vital Connect-account verwijderd.",
  },
};

const SECURITY_NOTE = "Was jij dit niet? Wijzig dan meteen je wachtwoord in de app, of neem contact met ons op.";
const IGNORE_NOTE = "Heb je dit niet aangevraagd? Dan kun je deze e-mail negeren.";

// Alleen een code, nooit een link: beveiligingsscanners van school- en werkmail (zoals Microsoft Defender) openen links
// in binnenkomende mail automatisch, waarmee de eenmalige code al verbruikt is voordat je hem intypt (lessen uit BOA Paraat).
// De app vult de code zelf in (account maken, inloggen per mail, nieuw wachtwoord).
function render(c: Copy, code?: string, notice = false) {
  const note = notice ? SECURITY_NOTE : IGNORE_NOTE;
  const html = `<!DOCTYPE html><html lang="nl"><body style="margin:0;background:#DCE6E1;font-family:Inter,Segoe UI,Roboto,Arial,sans-serif;color:#121316">
<table role="presentation" width="100%" cellpadding="0" cellspacing="0"><tr><td align="center" style="padding:32px 16px">
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="max-width:520px;background:#ffffff;border-radius:18px">
<tr><td style="background:#1C4D46;border-radius:18px 18px 0 0;padding:22px 28px;color:#ffffff;font-size:20px;font-weight:700">Vital Connect</td></tr>
<tr><td style="padding:28px">
<p style="margin:0 0 20px;font-size:16px;line-height:1.55">${esc(c.intro)}</p>
${code ? `<p style="margin:0 0 6px;font-size:14px;color:#5b5e66">${esc(c.codeLabel ?? "Je code")}</p>
<p style="margin:0 0 24px;font-size:34px;font-weight:700;letter-spacing:6px;color:#1C4D46">${esc(code)}</p>` : ""}
<p style="margin:0;font-size:14px;line-height:1.5;color:#5b5e66">${esc(note)}</p>
</td></tr></table>
<p style="margin:16px 0 0;font-size:13px;color:#5b5e66">Vital Connect · je gezondheidsdata op één plek</p>
</td></tr></table></body></html>`;
  const text = [c.intro, code ? `${c.codeLabel ?? "Je code"}: ${code}` : "", note]
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
    const c = COPY[type] ?? { subject: "Bericht van Vital Connect", intro: "Er is een wijziging in je Vital Connect-account." };
    const isNotice = type.endsWith("_notification");
    if (type === "email_change" && user.new_email && data.token_new) {
      // Veilige wijziging: twee mails, elk met een eigen code
      const cur = render(c, data.token);
      const nxt = render(c, data.token_new);
      await send(user.email, c.subject, cur.html, cur.text);
      await send(user.new_email, c.subject, nxt.html, nxt.text);
    } else {
      const to = type === "email_change" && user.new_email ? user.new_email : user.email;
      const m = render(c, isNotice ? undefined : (data.token || data.token_new), isNotice);
      await send(to, c.subject, m.html, m.text);
    }
    return new Response("{}", { status: 200, headers: { "Content-Type": "application/json" } });
  } catch (e) {
    console.error("send", e);
    return hookError(500, "E-mail versturen mislukt");
  }
});
