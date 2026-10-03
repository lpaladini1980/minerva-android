package it.paladia.minerva;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * The app's settings. The Argo password is encrypted with an AES-GCM key that lives in the
 * Android Keystore (never leaves the secure hardware, platform API: no AndroidX). allowBackup is off in the manifest.
 * School data is never stored: only these settings are.
 */
final class Settings {
    private static final String PREFS = "settings";
    private static final String KEY_ALIAS = "minerva-settings";

    String school = "";
    String username = "";
    String password = "";

    boolean complete() {
        return !school.isEmpty() && !username.isEmpty() && !password.isEmpty();
    }

    static Settings load(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Settings s = new Settings();
        s.school = p.getString("school", "");
        s.username = p.getString("username", "");
        s.password = decrypt(p.getString("password", ""));
        return s;
    }

    void save(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("school", school.trim())
                .putString("username", username.trim())
                .putString("password", encrypt(password))
                .remove("paladia_url")  // left by version 0.1
                .remove("token")
                .apply();
    }

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) return ((KeyStore.SecretKeyEntry) ks.getEntry(KEY_ALIAS, null)).getSecretKey();
        KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        gen.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return gen.generateKey();
    }

    private static String encrypt(String plain) {
        if (plain.isEmpty()) return "";
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = c.getIV();
            byte[] data = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(data, Base64.NO_WRAP);
        } catch (Exception e) {
            throw new IllegalStateException("Keystore encryption failed", e);
        }
    }

    private static String decrypt(String stored) {
        if (stored.isEmpty() || !stored.contains(":")) return "";
        try {
            String[] parts = stored.split(":", 2);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
            return new String(c.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";  // key lost (app data cleared, new phone): the user types the password again
        }
    }
}
