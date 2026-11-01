package org.ttrmobile;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/** Protocol state machine, independent of Android UI and transport. */
public final class TtrLoginFlow {
    public interface Transport { Map<String, String> post(Map<String, String> fields) throws Exception; }
    public interface Prompts {
        String code(String banner) throws Exception;
        void queue(String position) throws Exception;
        void waitForQueue() throws Exception;
    }
    public static final class Session {
        public final String server, cookie, manifest;
        Session(String server, String cookie, String manifest) { this.server = server; this.cookie = cookie; this.manifest = manifest; }
    }
    private TtrLoginFlow() { }
    public static Map<String, String> fields(String a, String av, String b, String bv) {
        Map<String, String> result = new HashMap<>(); result.put(a, av); result.put(b, bv); return result;
    }
    public static Session login(String username, String password, Transport transport, Prompts prompts) throws Exception {
        Map<String, String> request = fields("username", username, "password", password);
        Map<String, String> response;
        try { response = transport.post(request); } finally { request.clear(); }
        String queueToken = null;
        int queuePolls = 0, challenges = 0;
        for (;;) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            String success = response.get("success");
            if ("true".equals(success)) {
                String server = response.get("gameserver"), cookie = response.get("cookie"), manifest = response.get("manifest");
                TtrPaths.server(server); TtrPaths.sessionValue(cookie);
                if (manifest == null || !manifest.matches("/content/[A-Za-z0-9_.-]+")) throw new IOException("Unexpected game update manifest.");
                return new Session(server, cookie, manifest);
            }
            if ("false".equals(success)) {
                String banner = response.get("banner");
                throw new Rejected(banner == null || banner.isEmpty() ? "TTR rejected this login." : banner);
            }
            if ("partial".equals(success)) {
                if (++challenges > 10) throw new IOException("Too many verification attempts. Sign in again.");
                String token = TtrPaths.sessionValue(response.get("responseToken"));
                String code = prompts.code(response.get("banner"));
                if (code == null || code.trim().isEmpty()) throw new InterruptedException();
                request = fields("appToken", code.trim(), "authToken", token);
            } else if ("delayed".equals(success)) {
                if (++queuePolls > 120) throw new IOException("The login queue timed out. Please try again.");
                if (response.containsKey("queueToken")) queueToken = TtrPaths.sessionValue(response.get("queueToken"));
                if (queueToken == null) throw new IOException("The login queue did not provide a token.");
                prompts.queue(response.get("position")); prompts.waitForQueue();
                request = new HashMap<>(); request.put("queueToken", queueToken);
            } else throw new IOException("Unexpected response from the TTR login service.");
            try { response = transport.post(request); } finally { request.clear(); }
        }
    }
    public static final class Rejected extends IOException { public Rejected(String message) { super(message); } }
}
