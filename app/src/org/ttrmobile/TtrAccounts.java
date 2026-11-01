package org.ttrmobile;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONArray;
import org.json.JSONObject;

/** Entire account list is encrypted with a non-exportable Android Keystore key. */
public final class TtrAccounts {
    private final String alias;
    private static final byte[] AAD = "TTR Mobile accounts v1".getBytes(StandardCharsets.UTF_8);
    private final AtomicFile file;
    public TtrAccounts(Context c) { this(c, "accounts.v1", "org.ttrmobile.accounts.v1"); }
    TtrAccounts(Context c, String filename, String keyAlias) { file = new AtomicFile(new File(c.getNoBackupFilesDir(), filename)); alias = keyAlias; }
    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (!store.containsAlias(alias)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build());
            generator.generateKey();
        }
        return (SecretKey)store.getKey(alias, null);
    }
    private JSONArray read() throws Exception {
        if (!file.getBaseFile().exists() && !new File(file.getBaseFile() + ".bak").exists()) return new JSONArray();
        byte[] encrypted = file.readFully();
        if (encrypted.length < 30 || encrypted.length > 262144 || encrypted[0] != 1) throw new IOException("Saved accounts could not be unlocked.");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Arrays.copyOfRange(encrypted, 1, 13)));
        cipher.updateAAD(AAD);
        byte[] plain = cipher.doFinal(encrypted, 13, encrypted.length - 13);
        try { return new JSONArray(new String(plain, StandardCharsets.UTF_8)); }
        finally { Arrays.fill(plain, (byte)0); }
    }
    private void write(JSONArray accounts) throws Exception {
        byte[] plain = accounts.toString().getBytes(StandardCharsets.UTF_8);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key()); cipher.updateAAD(AAD);
        FileOutputStream out = null;
        try {
            byte[] encrypted = cipher.doFinal(plain);
            out = file.startWrite(); out.write(1); out.write(cipher.getIV()); out.write(encrypted); file.finishWrite(out);
        } catch (Exception e) { if (out != null) file.failWrite(out); throw e; }
        finally { Arrays.fill(plain, (byte)0); }
    }
    public synchronized List<String> usernames() throws Exception {
        JSONArray accounts = read(); List<String> names = new ArrayList<>();
        for (int i = 0; i < accounts.length(); i++) names.add(accounts.getJSONObject(i).getString("username"));
        return names;
    }
    public synchronized java.util.Map<String,Long> overview() throws Exception {
        JSONArray accounts=read();java.util.Map<String,Long> result=new java.util.LinkedHashMap<>();
        for(int i=0;i<accounts.length();i++){JSONObject a=accounts.getJSONObject(i);result.put(a.getString("username"),a.optLong("verified",0));}return result;
    }
    public synchronized String selected() throws Exception {
        JSONArray accounts=read();for(int i=0;i<accounts.length();i++){JSONObject a=accounts.getJSONObject(i);if(a.optBoolean("selected",false))return a.getString("username");}return "";
    }
    public synchronized void select(String username) throws Exception {
        JSONArray accounts=read();boolean changed=false;
        for(int i=0;i<accounts.length();i++){JSONObject a=accounts.getJSONObject(i);boolean selected=username.equals(a.getString("username"));if(selected!=a.optBoolean("selected",false)){a.put("selected",selected);changed=true;}}
        if(changed)write(accounts);
    }
    public synchronized String password(String username) throws Exception {
        JSONArray accounts = read();
        for (int i = 0; i < accounts.length(); i++) {
            JSONObject a = accounts.getJSONObject(i);
            if (a.getString("username").equals(username)) return a.getString("password");
        }
        return "";
    }
    public synchronized void save(String username, String password) throws Exception {
        JSONArray existing = read(), result = new JSONArray();
        for (int i = 0; i < existing.length(); i++)
            if (!existing.getJSONObject(i).getString("username").equals(username)) result.put(existing.getJSONObject(i));
        if (result.length() >= 20) throw new IOException("Remove a saved account before adding another.");
        result.put(new JSONObject().put("username", username).put("password", password).put("verified", 0)); write(result);
    }
    public synchronized long verified(String username) throws Exception {
        JSONArray all = read(); for (int i = 0; i < all.length(); i++) if (username.equals(all.getJSONObject(i).getString("username"))) return all.getJSONObject(i).optLong("verified", 0); return 0;
    }
    public synchronized void markVerified(String username, String password) throws Exception {
        JSONArray all = read(); for (int i = 0; i < all.length(); i++) {
            JSONObject account = all.getJSONObject(i);
            if (username.equals(account.getString("username")) && password.equals(account.getString("password"))) account.put("verified", System.currentTimeMillis());
        }
        write(all);
    }
    public synchronized void remove(String username) throws Exception {
        JSONArray existing = read(), result = new JSONArray();
        for (int i = 0; i < existing.length(); i++)
            if (!existing.getJSONObject(i).getString("username").equals(username)) result.put(existing.getJSONObject(i));
        if (result.length() == 0) file.delete(); else write(result);
    }
    public synchronized void clear() { file.delete(); }
}
