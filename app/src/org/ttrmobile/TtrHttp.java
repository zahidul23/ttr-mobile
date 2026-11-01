package org.ttrmobile;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;

/** Official HTTPS endpoints only. Never expose response bodies or request fields to logs. */
public final class TtrHttp {
    public static final String LOGIN = "https://www.toontownrewritten.com/api/login?format=json";
    public static final String MANIFEST = "https://cdn.toontownrewritten.com/content/patchmanifest.txt";
    public static final String MIRRORS = "https://cdn.toontownrewritten.com/mirrors.txt";
    /** Optional platform diagnostic; null keeps the default HTTPS transport. */
    public static volatile javax.net.ssl.SSLSocketFactory loginSocketFactory;
    public static volatile boolean loginUsedProxy;
    public static final java.util.concurrent.atomic.AtomicInteger loginRequests=new java.util.concurrent.atomic.AtomicInteger();
    private TtrHttp() { }
    public static URL official(String url) throws IOException {
        URL target = new URL(url);
        String host = target.getHost().toLowerCase(java.util.Locale.ROOT);
        if (!target.getProtocol().equals("https") || target.getUserInfo() != null
                || (target.getPort() != -1 && target.getPort() != 443)
                || !(host.equals("toontownrewritten.com") || host.endsWith(".toontownrewritten.com")))
            throw new IOException("An update URL is outside the official TTR service.");
        return target;
    }
    public static HttpsURLConnection open(String url) throws IOException {
        checkCancelled();
        HttpsURLConnection c = (HttpsURLConnection)official(url).openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(20000); c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "TTR-Mobile/0.4.1 (unofficial Android launcher)");
        return c;
    }
    public static void checkCancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Cancelled.");
    }
    public static String get(String url, int limit) throws Exception {
        HttpsURLConnection c = open(url);
        try {
            if (c.getResponseCode() != 200) throw new IOException("The TTR update service is unavailable. Try again later.");
            try (InputStream in = c.getInputStream()) { return read(in, limit); }
        } finally { c.disconnect(); }
    }
    public static JSONObject login(Map<String, String> fields) throws Exception {
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> field : fields.entrySet()) {
            if (body.length() > 0) body.append('&');
            body.append(URLEncoder.encode(field.getKey(), "UTF-8")).append('=')
                .append(URLEncoder.encode(field.getValue(), "UTF-8"));
        }
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        HttpsURLConnection c = open(LOGIN);
        c.setUseCaches(false);
        if(loginSocketFactory!=null)c.setSSLSocketFactory(loginSocketFactory);
        try {
            c.setRequestMethod("POST"); c.setDoOutput(true);
            loginRequests.incrementAndGet();
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            c.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = c.getOutputStream()) { out.write(bytes); }
            int code = c.getResponseCode();
            loginUsedProxy=c.usingProxy();
            if (code != 200) throw new IOException("The TTR login service is unavailable (HTTP " + code + "). Try again later.");
            try (InputStream in = c.getInputStream()) { return new JSONObject(read(in, 65536)); }
        } finally { java.util.Arrays.fill(bytes, (byte)0); c.disconnect(); }
    }
    private static String read(InputStream in, int limit) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192]; int n;
        while ((n = in.read(buffer)) != -1) {
            checkCancelled();
            if (out.size() + n > limit) throw new IOException("The TTR service returned an oversized response.");
            out.write(buffer, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
