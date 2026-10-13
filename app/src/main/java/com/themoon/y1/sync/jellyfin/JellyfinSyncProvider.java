package com.themoon.y1.sync.jellyfin;

import android.content.Context;
import android.content.SharedPreferences;

import com.themoon.y1.sync.MediaSyncProvider;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Jellyfin implementation of MediaSyncProvider.
 */
public class JellyfinSyncProvider implements MediaSyncProvider {

    private static final String PREFS_NAME = "Y1_JELLYFIN";
    private static JellyfinSyncProvider instance;

    private final Context context;
    private final SharedPreferences prefs;

    private JellyfinSyncProvider(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static synchronized JellyfinSyncProvider getInstance(Context context) {
        if (instance == null) {
            instance = new JellyfinSyncProvider(context);
        }
        return instance;
    }

    @Override
    public String getId() {
        return "jellyfin";
    }

    @Override
    public String getDisplayName() {
        return "Jellyfin";
    }

    public String getServerUrl() {
        return prefs.getString("server_url", "");
    }

    public void setServerUrl(String url) {
        prefs.edit().putString("server_url", JellyfinClient.normalizeUrl(url)).apply();
    }

    public String getAuthMode() {
        return prefs.getString("auth_mode", "quickconnect"); // "quickconnect", "credentials", or "token"
    }

    public void setAuthMode(String mode) {
        prefs.edit().putString("auth_mode", mode).apply();
    }

    public String getUsername() {
        return prefs.getString("username", "");
    }

    public void setUsername(String username) {
        prefs.edit().putString("username", username).apply();
    }

    public String getPassword() {
        return prefs.getString("password", "");
    }

    public void setPassword(String password) {
        prefs.edit().putString("password", password).apply();
    }

    public String getAccessToken() {
        return prefs.getString("access_token", "");
    }

    public void setAccessToken(String token) {
        prefs.edit().putString("access_token", token).apply();
    }

    public String getUserId() {
        return prefs.getString("user_id", "");
    }

    public void setUserId(String userId) {
        prefs.edit().putString("user_id", userId).apply();
    }

    public String getServerName() {
        return prefs.getString("server_name", "");
    }

    public void setServerName(String serverName) {
        prefs.edit().putString("server_name", serverName).apply();
    }

    public boolean isDownloadCoversEnabled() {
        return prefs.getBoolean("download_covers", true);
    }

    public void setDownloadCoversEnabled(boolean enabled) {
        prefs.edit().putBoolean("download_covers", enabled).apply();
    }

    public boolean isRemoveDeletedEnabled() {
        return prefs.getBoolean("remove_deleted", false);
    }

    public void setRemoveDeletedEnabled(boolean enabled) {
        prefs.edit().putBoolean("remove_deleted", enabled).apply();
    }

    public boolean isTranscodeVideoEnabled() {
        return prefs.getBoolean("transcode_video", false);
    }

    public void setTranscodeVideoEnabled(boolean enabled) {
        prefs.edit().putBoolean("transcode_video", enabled).apply();
    }

    public String getDeviceId() {
        String id = prefs.getString("device_id", "");
        if (id.isEmpty()) {
            id = UUID.randomUUID().toString();
            prefs.edit().putString("device_id", id).apply();
        }
        return id;
    }

    @Override
    public boolean isConfigured() {
        String url = getServerUrl();
        if (url.isEmpty()) return false;
        if ("token".equalsIgnoreCase(getAuthMode()) || "quickconnect".equalsIgnoreCase(getAuthMode())) {
            return !getAccessToken().isEmpty();
        } else {
            return !getUsername().isEmpty() && (!getPassword().isEmpty() || !getAccessToken().isEmpty());
        }
    }

    @Override
    public String getStatusSummary() {
        if (!isConfigured()) {
            return "Not Configured";
        }
        String user = getUsername();
        if (!user.isEmpty()) {
            return "@" + user;
        }
        return "Connected";
    }

    public JellyfinClient createClient() {
        return new JellyfinClient(getServerUrl(), getDeviceId());
    }

    @Override
    public void testConnection(final ConnectionCallback callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String url = getServerUrl();
                    if (url.isEmpty()) {
                        callback.onError("Server URL is empty");
                        return;
                    }

                    JellyfinClient client = createClient();
                    JellyfinClient.AuthResult auth;
                    if ("token".equalsIgnoreCase(getAuthMode()) || "quickconnect".equalsIgnoreCase(getAuthMode())) {
                        if (getAccessToken().isEmpty()) {
                            callback.onError("Not paired via QuickConnect yet");
                            return;
                        }
                        auth = client.verifyToken(getAccessToken());
                    } else {
                        auth = client.authenticateByName(getUsername(), getPassword());
                        setAccessToken(auth.accessToken);
                    }

                    setUserId(auth.userId);
                    setServerName(auth.serverName);
                    try {
                        List<LibraryInfo> libs = client.fetchLibraries(getAccessToken(), auth.userId, null);
                        if (libs != null && !libs.isEmpty()) {
                            setCachedLibraries(libs);
                        }
                    } catch (Exception ignored) {}
                    callback.onSuccess(auth.serverName, "OK");
                } catch (Exception e) {
                    callback.onError(e.getMessage() != null ? e.getMessage() : "Connection failed");
                }
            }
        }).start();
    }

    public synchronized String ensureValidAccessToken() throws Exception {
        String token = getAccessToken();
        if ("credentials".equalsIgnoreCase(getAuthMode())) {
            JellyfinClient client = createClient();
            try {
                if (!token.isEmpty()) {
                    client.verifyToken(token);
                    return token;
                }
            } catch (Exception e) {
                // Token invalid or expired, re-authenticate using saved username and password
            }
            if (!getUsername().isEmpty() && !getPassword().isEmpty()) {
                JellyfinClient.AuthResult auth = client.authenticateByName(getUsername(), getPassword());
                setAccessToken(auth.accessToken);
                setUserId(auth.userId);
                setServerName(auth.serverName);
                return auth.accessToken;
            }
        } else {
            if (getUserId().isEmpty() && !token.isEmpty()) {
                JellyfinClient client = createClient();
                try {
                    JellyfinClient.AuthResult auth = client.verifyToken(token);
                    if (auth.userId != null && !auth.userId.isEmpty()) {
                        setUserId(auth.userId);
                    }
                    if (auth.serverName != null && !auth.serverName.isEmpty()) {
                        setServerName(auth.serverName);
                    }
                } catch (Exception ignored) {}
            }
        }
        return token;
    }

    public synchronized List<LibraryInfo> getCachedLibraries() {
        String jsonStr = prefs.getString("cached_libraries", "");
        List<LibraryInfo> list = new ArrayList<>();
        if (jsonStr == null || jsonStr.isEmpty()) return list;
        try {
            JSONArray arr = new JSONArray(jsonStr);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                list.add(new LibraryInfo(
                        obj.optString("id", ""),
                        obj.optString("name", ""),
                        obj.optString("collectionType", "")
                ));
            }
        } catch (Exception ignored) {}
        return list;
    }

    public synchronized void setCachedLibraries(List<LibraryInfo> libraries) {
        if (libraries == null) return;
        try {
            JSONArray arr = new JSONArray();
            for (LibraryInfo lib : libraries) {
                JSONObject obj = new JSONObject();
                obj.put("id", lib.id);
                obj.put("name", lib.name);
                obj.put("collectionType", lib.collectionType);
                arr.put(obj);
            }
            prefs.edit().putString("cached_libraries", arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    @Override
    public List<LibraryInfo> fetchLibraries(CancellationToken token) throws Exception {
        String validToken = ensureValidAccessToken();
        JellyfinClient client = createClient();
        List<LibraryInfo> libs = client.fetchLibraries(validToken, getUserId(), token);
        if (libs != null && !libs.isEmpty()) {
            setCachedLibraries(libs);
        }
        return libs;
    }

    @Override
    public void clearConfiguration() {
        prefs.edit()
                .remove("server_url")
                .remove("auth_mode")
                .remove("username")
                .remove("password")
                .remove("access_token")
                .remove("user_id")
                .remove("server_name")
                .remove("cached_libraries")
                .apply();
    }
}
