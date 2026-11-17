package org.ttrmobile;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;

/**
 * Corporate Clash sign-in through its launcher API: the username and password are exchanged once for a launcher token
 * (kept encrypted with the account), and each sign-in trades that token for a one-time play cookie. When Clash asks
 * for ToonStep, the player approves the sign-in from the link Clash emails them and taps Continue.
 */
public final class TtrClashLogin {
    static final String API = "https://corporateclash.net/api/launcher/v1";
    public static final String GAME_SERVER = "gs-prd.corporateclash.net";
    /** Saved launcher token for the account; null clears it. */
    public interface Tokens { String get() throws Exception; void set(String token) throws Exception; }
    private TtrClashLogin() { }

    public static TtrLoginFlow.Session login(String username, String password, Tokens tokens, TtrLoginFlow.Prompts prompts) throws Exception {
        int badTokens = 0, approvals = 0;
        for (;;) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            String token = tokens.get(); TtrSecrets.add(token);
            if (token == null || token.isEmpty()) {
                Map<String, String> fields = new HashMap<>();
                fields.put("username", username); fields.put("password", password); fields.put("friendly", friendlyName());
                JSONObject reply;
                try { reply = TtrHttp.clash(API + "/register", fields, null); } finally { fields.clear(); }
                if (!reply.optBoolean("status", false) || reply.optString("token").isEmpty())
                    throw new TtrLoginFlow.Rejected(message(reply, "Corporate Clash rejected the username or password."));
                token = TtrPaths.sessionValue(reply.getString("token")); TtrSecrets.add(token);
                tokens.set(token);
            }
            JSONObject reply = TtrHttp.clash(API + "/login", new HashMap<>(), token);
            if (reply.optBoolean("status", false) && !reply.optString("token").isEmpty())
                { String cookie = TtrPaths.sessionValue(reply.getString("token")); TtrSecrets.add(cookie); return new TtrLoginFlow.Session(GAME_SERVER, cookie, null); }
            if (reply.optBoolean("toonstep", false)) {
                if (++approvals > 10) throw new java.io.IOException("Too many ToonStep attempts. Sign in again.");
                prompts.approve(message(reply, "Corporate Clash sent you an email to approve this sign-in (ToonStep)."));
                continue;
            }
            if (reply.optBoolean("bad_token", false)) {
                tokens.set(null);
                if (++badTokens >= 2) throw new TtrLoginFlow.Rejected("Corporate Clash rejected the launcher token.");
                continue;
            }
            throw new TtrLoginFlow.Rejected(message(reply, "Corporate Clash login failed."));
        }
    }
    private static String message(JSONObject reply, String fallback) {
        String m = reply.optString("message", "").trim();
        return m.isEmpty() || m.length() > 400 ? fallback : m;
    }
    /** Name this phone's token is listed under in the Clash account's launcher list. */
    private static String friendlyName() {
        byte[] id = new byte[9]; new SecureRandom().nextBytes(id);
        return "TTR-Mobile-" + android.util.Base64.encodeToString(id, android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
    }
}
