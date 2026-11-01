package org.ttrmobile;

import java.io.File;
import java.io.IOException;
import java.util.*;

public final class TtrLoginTest {
    private static Map<String, String> result(String... pairs) {
        Map<String, String> out = new HashMap<>(); for (int i = 0; i < pairs.length; i += 2) out.put(pairs[i], pairs[i + 1]); return out;
    }
    private static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        List<Map<String, String>> sent = new ArrayList<>();
        List<Map<String, String>> responses = Arrays.asList(
            result("success", "partial", "responseToken", "first", "banner", "ToonGuard"),
            result("success", "partial", "responseToken", "second", "banner", "Authenticator"),
            result("success", "delayed", "queueToken", "queue-1", "position", "2"),
            result("success", "delayed", "position", "1"),
            result("success", "true", "cookie", "synthetic-cookie", "gameserver", "[2001:db8::1]:7198;gameserver.toontownrewritten.com:7198", "manifest", "/content/patchmanifest.txt"));
        final int[] code = {0}, wait = {0};
        TtrLoginFlow.Session session = TtrLoginFlow.login("test+user", "p&ss=word", fields -> {
            sent.add(new HashMap<>(fields)); return responses.get(sent.size() - 1);
        }, new TtrLoginFlow.Prompts() {
            public String code(String banner) { check(banner.equals(++code[0] == 1 ? "ToonGuard" : "Authenticator")); return "code" + code[0]; }
            public void queue(String position) { check(position.equals(wait[0] == 0 ? "2" : "1")); }
            public void waitForQueue() { wait[0]++; }
        });
        check(session.cookie.equals("synthetic-cookie") && code[0] == 2 && wait[0] == 2);
        check(session.server.equals("[2001:db8::1]:7198;gameserver.toontownrewritten.com:7198"));
        check(sent.get(0).equals(result("username", "test+user", "password", "p&ss=word")));
        check(sent.get(1).equals(result("appToken", "code1", "authToken", "first")));
        check(sent.get(2).equals(result("appToken", "code2", "authToken", "second")));
        check(sent.get(3).equals(result("queueToken", "queue-1")) && sent.get(4).equals(sent.get(3)));
        TtrLoginFlow.Prompts unused = new TtrLoginFlow.Prompts() {
            public String code(String banner) { return null; } public void queue(String p) { } public void waitForQueue() { }
        };
        try { TtrLoginFlow.login("u", "p", fields -> result("success", "false", "banner", "Rejected"), unused); throw new AssertionError(); }
        catch (TtrLoginFlow.Rejected e) { check(e.getMessage().equals("Rejected")); }
        for (Map<String, String> invalid : Arrays.asList(result("success", "delayed"), result("success", "unknown"),
                result("success", "true", "cookie", "bad\ncookie", "gameserver", "toontownrewritten.com", "manifest", "/content/test"),
                result("success", "true", "cookie", "test", "gameserver", "https://example.test/path", "manifest", "/content/test"),
                result("success", "true", "cookie", "test", "gameserver", "toontownrewritten.com", "manifest", "https://evil.test/manifest"))) {
            try { TtrLoginFlow.login("u", "p", f -> invalid, unused); throw new AssertionError(); } catch (IOException expected) { }
        }
        try { TtrLoginFlow.login("u", "p", f -> result("success", "partial", "responseToken", "challenge"), unused); throw new AssertionError(); }
        catch (InterruptedException expected) { }
        for (String endpoint : Arrays.asList("gameserver-alpha.toontownrewritten.com:7198", "gameserver.example.net:7198", "192.0.2.1:7198", "[2001:db8::1]:7198")) TtrPaths.server(endpoint);
        TtrPaths.server("[2604:a880:400:d1:0:1:598a:c001]:7198;gameserver.toontownrewritten.com:7198");
        for (String endpoint : Arrays.asList("host:0", "host:65536", "host;", ";host", "host;;fallback", "host;fallback:0", "host;bad/route", "host/route", "user@host", "host name", "host\nother", "host\u0000other", new String(new char[512]).replace('\u0000', 'a'))) {
            try { TtrPaths.server(endpoint); throw new AssertionError(endpoint); } catch (IOException expected) { }
        }
        File root = new File(args[0]); check(TtrPaths.child(root, "TTREngine64.exe").getParentFile().equals(root.getCanonicalFile()));
        for (String name : Arrays.asList("../escape", "..", ".", "C:\\evil", "/absolute", "a/b", "a\\b", "control\n")) {
            try { TtrPaths.child(root, name); throw new AssertionError(name); } catch (IOException expected) { }
        }
        System.out.println("PASS: credentials/challenge/queue request fields, rotating challenge tokens, retained queue token, cancellation, failures, session/server validation, path confinement");
    }
}
