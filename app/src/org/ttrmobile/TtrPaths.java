package org.ttrmobile;

import java.io.File;
import java.io.IOException;

/** Shared validation for manifest targets and the private session handoff. */
public final class TtrPaths {
    private TtrPaths() { }
    public static File child(File root, String name) throws IOException {
        if (!name.matches("[A-Za-z0-9_ .-]{1,180}") || name.equals(".") || name.equals(".."))
            throw new IOException("The update manifest contains an unsafe filename.");
        File result = new File(root, name).getCanonicalFile();
        if (!root.getCanonicalFile().equals(result.getParentFile()))
            throw new IOException("The update path leaves the game directory.");
        return result;
    }
    public static String sessionValue(String value) throws IOException {
        if (value == null || value.isEmpty() || value.length() > 8192)
            throw new IOException("The login server returned an invalid session.");
        for (int i = 0; i < value.length(); i++)
            if (value.charAt(i) < 33 || value.charAt(i) > 126)
                throw new IOException("The login server returned an invalid session.");
        return value;
    }
    public static void server(String value) throws IOException {
        sessionValue(value);
        // TTR returns an ordered, semicolon-separated list (for example IPv6
        // followed by a DNS fallback). Validate each endpoint, but preserve the
        // entire value unchanged for the engine's TTR_GAMESERVER environment.
        if (value.length() > 511)
            throw new IOException("The login server returned an unexpected game server.");
        for (String endpoint : value.split(";", -1)) {
            if (endpoint.length() > 255 || !endpoint.matches("(?i)([a-z0-9](?:[a-z0-9.-]*[a-z0-9])?|\\[[0-9a-f:]+\\])(:[0-9]{1,5})?"))
                throw new IOException("The login server returned an unexpected game server.");
            int colon = endpoint.lastIndexOf(':');
            if (colon > endpoint.lastIndexOf(']') && colon >= 0) {
                int port = Integer.parseInt(endpoint.substring(colon + 1));
                if (port < 1 || port > 65535) throw new IOException("The login server returned an invalid game port.");
            }
        }
    }
}
