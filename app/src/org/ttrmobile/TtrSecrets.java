package org.ttrmobile;

import java.util.*;

/**
 * Login values seen by this process (play cookies, verification and queue tokens). Logs and shared diagnostics
 * replace them wherever they appear, so a secret is hidden even on a line without a telltale word. Passwords are
 * never added: they are never logged, and keeping them here would only make them live longer in memory.
 */
public final class TtrSecrets {
    private TtrSecrets() { }
    private static final Set<String> values = new LinkedHashSet<>();
    public static synchronized void add(String value) {
        if (value == null || value.length() < 8) return;
        values.add(value);
        if (values.size() > 32) { Iterator<String> oldest = values.iterator(); oldest.next(); oldest.remove(); }
    }
    public static synchronized String redact(String text) {
        if (text == null || values.isEmpty()) return text;
        for (String value : values) if (text.contains(value)) text = text.replace(value, "[hidden]");
        return text;
    }
}
