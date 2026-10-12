package com.themoon.y1.sync.jellyfin;

import android.os.Build;
import android.util.Log;

import com.themoon.y1.sync.MediaSyncProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URLEncoder;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * REST API client for Jellyfin with Conscrypt TLS and low-RAM streaming support.
 */
public class JellyfinClient {

    private static final String TAG = "JellyfinClient";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

    private final String baseUrl;
    private final String deviceId;
    private final OkHttpClient httpClient;

    public JellyfinClient(String rawUrl, String deviceId) {
        this.baseUrl = normalizeUrl(rawUrl);
        this.deviceId = (deviceId != null && !deviceId.isEmpty()) ? deviceId : "InnioasisY2Device";
        this.httpClient = buildOkHttpClient();
    }

    public static String normalizeUrl(String raw) {
        if (raw == null) return "";
        String url = raw.trim();
        if (url.isEmpty()) return "";
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://" + url;
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    private OkHttpClient buildOkHttpClient() {
        try {
            final X509TrustManager trustAll = new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            };

            SSLContext sslContext = SSLContext.getInstance("TLS", "Conscrypt");
            sslContext.init(null, new TrustManager[]{trustAll}, new SecureRandom());

            return new OkHttpClient.Builder()
                    .sslSocketFactory(sslContext.getSocketFactory(), trustAll)
                    .hostnameVerifier(new HostnameVerifier() {
                        @Override
                        public boolean verify(String hostname, SSLSession session) {
                            return true;
                        }
                    })
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .writeTimeout(60, TimeUnit.SECONDS)
                    .build();
        } catch (Exception e) {
            Log.w(TAG, "Conscrypt SSL init failed, fallback to default OkHttp: " + e.getMessage());
            return new OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .build();
        }
    }

    private String getAuthHeader(String token) {
        StringBuilder sb = new StringBuilder("MediaBrowser Client=\"InniClassic\", Device=\"Innioasis Y2\", DeviceId=\"")
                .append(deviceId).append("\", Version=\"1.0\"");
        if (token != null && !token.isEmpty()) {
            sb.append(", Token=\"").append(token).append("\"");
        }
        return sb.toString();
    }

    private Request.Builder newRequestBuilder(String url, String token) {
        Request.Builder builder = new Request.Builder()
                .url(url)
                .addHeader("Authorization", getAuthHeader(token))
                .addHeader("X-Emby-Authorization", getAuthHeader(token));
        if (token != null && !token.isEmpty()) {
            builder.addHeader("X-Emby-Token", token);
        }
        return builder;
    }

    public static class AuthResult {
        public final String accessToken;
        public final String userId;
        public final String serverId;
        public final String serverName;

        public AuthResult(String accessToken, String userId, String serverId, String serverName) {
            this.accessToken = accessToken;
            this.userId = userId;
            this.serverId = serverId;
            this.serverName = serverName;
        }
    }

    /**
     * Authenticate using username and password.
     */
    public AuthResult authenticateByName(String username, String password) throws Exception {
        JSONObject bodyJson = new JSONObject();
        bodyJson.put("Username", username != null ? username : "");
        bodyJson.put("Pw", password != null ? password : "");

        RequestBody body = RequestBody.create(JSON_MEDIA_TYPE, bodyJson.toString());
        Request request = newRequestBuilder(baseUrl + "/Users/AuthenticateByName", null)
                .post(body)
                .build();

        Response response = httpClient.newCall(request).execute();
        if (!response.isSuccessful()) {
            int code = response.code();
            String errorMsg = "";
            try {
                if (response.body() != null) {
                    errorMsg = response.body().string().trim();
                }
            } catch (Exception ignored) {}
            response.close();
            if (code == 401) {
                throw new Exception("Invalid username or password");
            } else {
                throw new Exception("Authentication failed (HTTP " + code + (errorMsg.isEmpty() ? "" : ": " + errorMsg) + ")");
            }
        }

        String resStr = response.body().string();
        JSONObject resObj = new JSONObject(resStr);
        String token = resObj.getString("AccessToken");
        String serverId = resObj.optString("ServerId", "");

        JSONObject userObj = resObj.getJSONObject("User");
        String userId = userObj.getString("Id");
        String serverName = userObj.optString("Name", "Jellyfin");

        return new AuthResult(token, userId, serverId, serverName);
    }

    public static class QuickConnectSession {
        public final String code;
        public final String secret;
        public final boolean authenticated;

        public QuickConnectSession(String code, String secret, boolean authenticated) {
            this.code = code;
            this.secret = secret;
            this.authenticated = authenticated;
        }
    }

    /**
     * Check if QuickConnect is enabled on the server.
     */
    public boolean isQuickConnectEnabled() {
        try {
            Request request = newRequestBuilder(baseUrl + "/QuickConnect/Enabled", null)
                    .get()
                    .build();
            Response response = httpClient.newCall(request).execute();
            if (response.isSuccessful() && response.body() != null) {
                String str = response.body().string().trim();
                response.close();
                return Boolean.parseBoolean(str);
            }
            response.close();
        } catch (Exception ignored) {}
        return false;
    }

    /**
     * Initiate a QuickConnect session.
     */
    public QuickConnectSession initiateQuickConnect() throws Exception {
        RequestBody emptyBody = RequestBody.create(MediaType.parse("application/x-www-form-urlencoded"), "");
        Request request = newRequestBuilder(baseUrl + "/QuickConnect/Initiate", null)
                .post(emptyBody)
                .build();

        Response response = httpClient.newCall(request).execute();
        if (!response.isSuccessful()) {
            int code = response.code();
            response.close();
            throw new Exception("QuickConnect initiate failed (HTTP " + code + ")");
        }

        String resStr = response.body().string();
        JSONObject obj = new JSONObject(resStr);
        String code = obj.optString("Code", "");
        String secret = obj.optString("Secret", "");
        boolean auth = obj.optBoolean("Authenticated", false);

        return new QuickConnectSession(code, secret, auth);
    }

    /**
     * Poll state of QuickConnect session.
     */
    public QuickConnectSession checkQuickConnect(String secret) throws Exception {
        String url = baseUrl + "/QuickConnect/Connect?secret=" + URLEncoder.encode(secret, "UTF-8");
        Request request = newRequestBuilder(url, null)
                .get()
                .build();

        Response response = httpClient.newCall(request).execute();
        if (!response.isSuccessful()) {
            int code = response.code();
            response.close();
            throw new Exception("QuickConnect poll failed (HTTP " + code + ")");
        }

        String resStr = response.body().string();
        JSONObject obj = new JSONObject(resStr);
        String code = obj.optString("Code", "");
        boolean auth = obj.optBoolean("Authenticated", false);

        return new QuickConnectSession(code, secret, auth);
    }

    /**
     * Exchange authorized QuickConnect secret for an access token.
     */
    public AuthResult authenticateWithQuickConnect(String secret) throws Exception {
        JSONObject bodyJson = new JSONObject();
        bodyJson.put("Secret", secret);

        RequestBody body = RequestBody.create(JSON_MEDIA_TYPE, bodyJson.toString());
        Request request = newRequestBuilder(baseUrl + "/Users/AuthenticateWithQuickConnect", null)
                .post(body)
                .build();

        Response response = httpClient.newCall(request).execute();
        if (!response.isSuccessful()) {
            int code = response.code();
            String errorMsg = "";
            try {
                if (response.body() != null) {
                    errorMsg = response.body().string().trim();
                }
            } catch (Exception ignored) {}
            response.close();
            throw new Exception("QuickConnect authentication failed (HTTP " + code + (errorMsg.isEmpty() ? "" : ": " + errorMsg) + ")");
        }

        String resStr = response.body().string();
        JSONObject resObj = new JSONObject(resStr);
        String token = resObj.getString("AccessToken");
        String serverId = resObj.optString("ServerId", "");

        JSONObject userObj = resObj.getJSONObject("User");
        String userId = userObj.getString("Id");
        String serverName = userObj.optString("Name", "Jellyfin");

        return new AuthResult(token, userId, serverId, serverName);
    }

    /**
     * Test direct API token authentication and retrieve userId.
     */
    public AuthResult verifyToken(String token) throws Exception {
        Request request = newRequestBuilder(baseUrl + "/System/Info", token)
                .get()
                .build();

        Response response = httpClient.newCall(request).execute();
        if (!response.isSuccessful()) {
            int code = response.code();
            String errorMsg = "";
            try {
                if (response.body() != null) {
                    errorMsg = response.body().string().trim();
                }
            } catch (Exception ignored) {}
            response.close();
            throw new Exception("Token verification failed (HTTP " + code + (errorMsg.isEmpty() ? "" : ": " + errorMsg) + ")");
        }

        String resStr = response.body().string();
        JSONObject infoObj = new JSONObject(resStr);
        String serverName = infoObj.optString("ServerName", "Jellyfin");
        String serverId = infoObj.optString("Id", "");

        // Fetch users to discover first user id
        Request usersReq = newRequestBuilder(baseUrl + "/Users", token)
                .get()
                .build();

        String userId = "";
        Response usersRes = httpClient.newCall(usersReq).execute();
        if (usersRes.isSuccessful()) {
            String usersStr = usersRes.body().string();
            JSONArray usersArr = new JSONArray(usersStr);
            if (usersArr.length() > 0) {
                userId = usersArr.getJSONObject(0).getString("Id");
            }
        } else {
            usersRes.close();
        }

        return new AuthResult(token, userId, serverId, serverName);
    }

    /**
     * Retrieve libraries via GET /Users/{userId}/Views.
     */
    public List<MediaSyncProvider.LibraryInfo> fetchLibraries(String token, String userId,
                                                              MediaSyncProvider.CancellationToken cancelToken) throws Exception {
        if (cancelToken != null && cancelToken.isCancelled()) return new ArrayList<>();

        String url = baseUrl + (userId != null && !userId.isEmpty() ? "/Users/" + userId + "/Views" : "/Library/VirtualFolders");
        Request request = newRequestBuilder(url, token)
                .get()
                .build();

        Response response = httpClient.newCall(request).execute();
        if (!response.isSuccessful()) {
            int code = response.code();
            response.close();
            throw new Exception("Failed to fetch libraries (HTTP " + code + ")");
        }

        String resStr = response.body().string();
        JSONObject root = new JSONObject(resStr);
        JSONArray items = root.optJSONArray("Items");
        List<MediaSyncProvider.LibraryInfo> list = new ArrayList<>();
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                if (cancelToken != null && cancelToken.isCancelled()) break;
                JSONObject item = items.getJSONObject(i);
                String id = item.optString("Id", item.optString("ItemId", ""));
                String name = item.optString("Name", "Library");
                String collType = item.optString("CollectionType", "mixed").toLowerCase();
                list.add(new MediaSyncProvider.LibraryInfo(id, name, collType));
            }
        }
        return list;
    }

    public static class ItemsQueryResult {
        public final int totalRecordCount;
        public final List<JellyfinMediaItem> items;

        public ItemsQueryResult(int totalRecordCount, List<JellyfinMediaItem> items) {
            this.totalRecordCount = totalRecordCount;
            this.items = items;
        }
    }

    /**
     * Fetch paginated media items from a library.
     */
    public ItemsQueryResult fetchItemsPage(String token, String userId, String libraryId, String collectionType,
                                          int startIndex, int limit, List<String> itemIds,
                                          MediaSyncProvider.CancellationToken cancelToken) throws Exception {
        if (cancelToken != null && cancelToken.isCancelled()) {
            return new ItemsQueryResult(0, new ArrayList<JellyfinMediaItem>());
        }

        StringBuilder urlBuilder = new StringBuilder(baseUrl);
        urlBuilder.append("/Users/").append(userId).append("/Items");
        urlBuilder.append("?parentId=").append(URLEncoder.encode(libraryId, "UTF-8"));
        urlBuilder.append("&recursive=true");
        urlBuilder.append("&startIndex=").append(startIndex);
        urlBuilder.append("&limit=").append(limit);
        urlBuilder.append("&fields=MediaSources,Genres,MediaStreams,Composers,DateCreated");

        if ("music".equalsIgnoreCase(collectionType)) {
            urlBuilder.append("&includeItemTypes=Audio");
            urlBuilder.append("&sortBy=AlbumArtist,Album,SortName");
        } else if ("movies".equalsIgnoreCase(collectionType)) {
            urlBuilder.append("&includeItemTypes=Movie");
            urlBuilder.append("&sortBy=SortName");
        } else if ("tvshows".equalsIgnoreCase(collectionType)) {
            urlBuilder.append("&includeItemTypes=Episode");
            urlBuilder.append("&sortBy=SeriesSortName,ParentIndexNumber,IndexNumber");
        }

        if (itemIds != null && !itemIds.isEmpty()) {
            StringBuilder idsStr = new StringBuilder();
            for (int i = 0; i < itemIds.size(); i++) {
                if (i > 0) idsStr.append(",");
                idsStr.append(itemIds.get(i));
            }
            urlBuilder.append("&ids=").append(URLEncoder.encode(idsStr.toString(), "UTF-8"));
        }

        Request request = newRequestBuilder(urlBuilder.toString(), token)
                .get()
                .build();

        Response response = httpClient.newCall(request).execute();
        if (!response.isSuccessful()) {
            int code = response.code();
            response.close();
            throw new Exception("Failed to query items (HTTP " + code + ")");
        }

        String resStr = response.body().string();
        JSONObject root = new JSONObject(resStr);
        int total = root.optInt("TotalRecordCount", 0);
        JSONArray itemsArr = root.optJSONArray("Items");

        List<JellyfinMediaItem> resultList = new ArrayList<>();
        if (itemsArr != null) {
            for (int i = 0; i < itemsArr.length(); i++) {
                if (cancelToken != null && cancelToken.isCancelled()) break;
                JSONObject o = itemsArr.getJSONObject(i);
                JellyfinMediaItem item = parseItem(o, libraryId, collectionType);
                resultList.add(item);
            }
        }

        return new ItemsQueryResult(total, resultList);
    }

    private JellyfinMediaItem parseItem(JSONObject o, String libraryId, String collectionType) {
        JellyfinMediaItem item = new JellyfinMediaItem();
        item.id = o.optString("Id", "");
        item.name = o.optString("Name", "Unknown");
        item.album = o.optString("Album", null);
        item.libraryId = libraryId;
        item.collectionType = collectionType;
        item.serverDateModified = o.optString("DateCreated", "");

        JSONArray artistsArr = o.optJSONArray("Artists");
        if (artistsArr != null && artistsArr.length() > 0) {
            item.artist = artistsArr.optString(0, null);
        }
        if (item.artist == null || item.artist.trim().isEmpty()) {
            item.artist = o.optString("AlbumArtist", "Unknown Artist");
        }

        item.albumArtist = o.optString("AlbumArtist", item.artist);

        JSONArray composersArr = o.optJSONArray("Composers");
        if (composersArr != null && composersArr.length() > 0) {
            JSONObject cObj = composersArr.optJSONObject(0);
            if (cObj != null) {
                item.composer = cObj.optString("Name", "Unknown Composer");
            }
        }
        if (item.composer == null || item.composer.trim().isEmpty()) {
            item.composer = "Unknown Composer";
        }

        int year = o.optInt("ProductionYear", 0);
        item.year = year > 0 ? String.valueOf(year) : "Unknown Year";

        JSONArray genresArr = o.optJSONArray("Genres");
        if (genresArr != null && genresArr.length() > 0) {
            item.genre = genresArr.optString(0, "Unknown Genre");
        } else {
            item.genre = "Unknown Genre";
        }

        item.indexNumber = o.optInt("IndexNumber", 0);
        item.parentIndexNumber = o.optInt("ParentIndexNumber", 0);
        item.seriesName = o.optString("SeriesName", null);
        item.albumId = o.optString("AlbumId", null);
        item.albumPrimaryImageTag = o.optString("AlbumPrimaryImageTag", null);

        item.container = o.optString("Container", "");

        JSONArray sources = o.optJSONArray("MediaSources");
        if (sources != null && sources.length() > 0) {
            JSONObject src = sources.optJSONObject(0);
            if (src != null) {
                item.fileSize = src.optLong("Size", 0);
                if (item.container.isEmpty()) {
                    item.container = src.optString("Container", "");
                }
                if (item.container.isEmpty()) {
                    String path = src.optString("Path", "");
                    int dot = path.lastIndexOf(".");
                    if (dot > 0 && dot < path.length() - 1) {
                        item.container = path.substring(dot + 1);
                    }
                }
            }
        }

        if (item.container.isEmpty()) {
            item.container = "music".equalsIgnoreCase(collectionType) ? "mp3" : "mp4";
        }

        return item;
    }

    /**
     * Download item (audio or video) to target file with atomic write safeguards and progress listener.
     */
    public void downloadItem(String token, JellyfinMediaItem item, boolean transcodeVideo,
                             File destination, MediaSyncProvider.DownloadProgressListener listener,
                             MediaSyncProvider.CancellationToken cancelToken) throws Exception {
        if (cancelToken != null && cancelToken.isCancelled()) return;

        String downloadUrl;
        if ("movies".equalsIgnoreCase(item.collectionType) || "tvshows".equalsIgnoreCase(item.collectionType)) {
            if (transcodeVideo) {
                downloadUrl = baseUrl + "/Videos/" + item.id + "/stream"
                        + "?static=false&videoCodec=h264&maxWidth=480&videoBitRate=1000000&audioCodec=aac&audioBitRate=128000&container=mp4";
            } else {
                downloadUrl = baseUrl + "/Items/" + item.id + "/Download";
            }
        } else {
            downloadUrl = baseUrl + "/Items/" + item.id + "/Download";
        }

        Request request = newRequestBuilder(downloadUrl, token)
                .addHeader("Accept-Encoding", "identity")
                .get()
                .build();

        Response response = httpClient.newCall(request).execute();
        if (!response.isSuccessful()) {
            int code = response.code();
            response.close();
            // If download permission denied on audio, try static audio stream fallback
            if ("music".equalsIgnoreCase(item.collectionType) && (code == 401 || code == 403)) {
                String fallbackUrl = baseUrl + "/Audio/" + item.id + "/stream?static=true";
                Request fallbackReq = newRequestBuilder(fallbackUrl, token)
                        .addHeader("Accept-Encoding", "identity")
                        .get()
                        .build();
                response = httpClient.newCall(fallbackReq).execute();
                if (!response.isSuccessful()) {
                    int fbCode = response.code();
                    response.close();
                    throw new Exception("Download failed (HTTP " + fbCode + ")");
                }
            } else {
                throw new Exception("Download failed (HTTP " + code + ")");
            }
        }

        ResponseBody body = response.body();
        if (body == null) {
            response.close();
            throw new Exception("Empty response body");
        }

        long contentLength = body.contentLength();
        if (contentLength <= 0 && item.fileSize > 0) {
            contentLength = item.fileSize;
        }

        File parent = destination.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        File tmpFile = new File(destination.getAbsolutePath() + ".tmp");
        if (tmpFile.exists()) {
            tmpFile.delete();
        }

        InputStream in = null;
        FileOutputStream out = null;
        try {
            in = body.byteStream();
            out = new FileOutputStream(tmpFile);
            byte[] buffer = new byte[8192];
            int read;
            long totalRead = 0;

            while ((read = in.read(buffer)) != -1) {
                if (cancelToken != null && cancelToken.isCancelled()) {
                    throw new Exception("Download cancelled");
                }
                out.write(buffer, 0, read);
                totalRead += read;
                if (listener != null) {
                    listener.onProgress(totalRead, contentLength);
                }
            }
            out.flush();
            out.close();
            out = null;

            if (destination.exists()) {
                destination.delete();
            }
            if (!tmpFile.renameTo(destination)) {
                throw new Exception("Failed to rename .tmp to " + destination.getName());
            }
        } finally {
            if (out != null) {
                try { out.close(); } catch (Exception ignored) {}
            }
            if (in != null) {
                try { in.close(); } catch (Exception ignored) {}
            }
            response.close();
            if (tmpFile.exists()) {
                tmpFile.delete();
            }
        }
    }

    /**
     * Download album cover art to folder.jpg.
     */
    public void downloadCover(String token, String albumId, String itemId, File destination,
                              MediaSyncProvider.CancellationToken cancelToken) throws Exception {
        if (cancelToken != null && cancelToken.isCancelled()) return;
        if (destination.exists()) return;

        String targetId = (albumId != null && !albumId.isEmpty()) ? albumId : itemId;
        if (targetId == null || targetId.isEmpty()) return;

        String coverUrl = baseUrl + "/Items/" + targetId + "/Images/Primary?maxWidth=320&maxHeight=320&quality=85";
        Request request = newRequestBuilder(coverUrl, token)
                .get()
                .build();

        Response response = httpClient.newCall(request).execute();
        if (!response.isSuccessful()) {
            response.close();
            return; // Ignore cover download failure
        }

        File parent = destination.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        File tmpFile = new File(destination.getAbsolutePath() + ".tmp");
        InputStream in = null;
        FileOutputStream out = null;
        try {
            in = response.body().byteStream();
            out = new FileOutputStream(tmpFile);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (cancelToken != null && cancelToken.isCancelled()) break;
                out.write(buffer, 0, read);
            }
            out.flush();
            out.close();
            out = null;

            if (cancelToken == null || !cancelToken.isCancelled()) {
                tmpFile.renameTo(destination);
            }
        } finally {
            if (out != null) {
                try { out.close(); } catch (Exception ignored) {}
            }
            if (in != null) {
                try { in.close(); } catch (Exception ignored) {}
            }
            response.close();
            if (tmpFile.exists()) {
                tmpFile.delete();
            }
        }
    }
}
