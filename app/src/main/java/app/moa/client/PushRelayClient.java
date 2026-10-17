package app.moa.client;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.json.JSONException;
import org.json.JSONObject;

/** Device-scoped relay credentials stay in private storage, separate from web sessions. */
public final class PushRelayClient {
    private static final String PREFS = "moa_push_relay";
    private static final Object LOCK = new Object();
    private static final int MAX_BYTES = 16 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ThreadPoolExecutor WORK = new ThreadPoolExecutor(1, 1, 30,
            TimeUnit.SECONDS, new ArrayBlockingQueue<>(8), task -> {
                Thread thread = new Thread(task, "moa-push-relay");
                thread.setDaemon(true);
                return thread;
            });
    private static long generation;

    static { WORK.allowCoreThreadTimeOut(true); }

    public interface Callback {
        void onSuccess(String message);
        void onError(String message);
    }

    private PushRelayClient() { }

    public static boolean isConfigured(Context context) {
        return endpoint(context.getApplicationContext()) != null;
    }

    public static boolean isPaired(Context context) {
        Context app = context.getApplicationContext();
        synchronized (LOCK) {
            return allowed(app) && credentials(app) != null && preferences(app).getBoolean("paired", false)
                    && !preferences(app).getBoolean("cleanup_pending", false);
        }
    }

    public static void pair(Context context, String code, String fcmToken, Callback callback) {
        Context app = context.getApplicationContext();
        String invite = code == null ? "" : code.trim();
        final Credentials device;
        final boolean recover;
        final boolean pendingCleanup;
        final long expected;
        synchronized (LOCK) {
            expected = ++generation;
            String problem = prerequisite(app);
            if (problem == null && !invite.matches("[A-Za-z0-9_-]{27}"))
                problem = "연결 코드를 다시 확인해 주세요.";
            if (problem == null && !validToken(fcmToken))
                problem = "푸시 연결 정보를 받지 못했어요. 다시 시도해 주세요.";
            Credentials stored = storedCredentials(app);
            if (problem == null && stored != null && !stored.endpoint.equals(endpoint(app)))
                problem = "이전 알림 서버 연결을 먼저 해제한 뒤 다시 연결해 주세요.";
            if (problem != null) {
                deliver(app, expected, callback, false, problem, false);
                return;
            }
            Credentials previous = credentials(app);
            recover = previous != null;
            pendingCleanup = previous != null && preferences(app).getBoolean("cleanup_pending", false);
            device = previous != null ? previous : new Credentials(endpoint(app), random(16), random(32));
            // Persist before sending: an interrupted/lost pairing response can be recovered with PUT.
            if (!preferences(app).edit().putString("endpoint", device.endpoint)
                    .putString("installation_id", device.id).putString("installation_secret", device.secret)
                    .putBoolean("paired", previous != null && preferences(app).getBoolean("paired", false)).commit()) {
                deliver(app, expected, callback, false, "연결 정보를 저장하지 못했어요. 저장 공간을 확인해 주세요.", true);
                return;
            }
        }
        submit(app, expected, callback, () -> {
            if (!current(app, expected, device, true)) return;
            try {
                // Keep old authentication until deletion is confirmed, so a disconnected FCM token
                // cannot remain claimed remotely while this device creates another pairing.
                if (pendingCleanup) {
                    if (!removeRemote(device)) {
                        deliver(app, expected, callback, false,
                                "이전 서버 연결을 아직 해제하지 못했어요. 네트워크를 확인하고 다시 시도해 주세요.", true);
                        return;
                    }
                    synchronized (LOCK) {
                        if (!current(app, expected, device, true)) return;
                        if (!preferences(app).edit().putBoolean("cleanup_pending", false).commit()) {
                            deliver(app, expected, callback, false, "연결 정보를 저장하지 못했어요. 다시 시도해 주세요.", true);
                            return;
                        }
                    }
                }
                if (recover && !pendingCleanup) {
                    Response recovery = request(device, "PUT", "/token", tokenBody(fcmToken), true);
                    if (recovery.is(200, "updated")) {
                        finishPair(app, expected, device, fcmToken, callback);
                        return;
                    }
                    if (recovery.status != 401 && recovery.status != 404) {
                        fail(app, expected, callback, recovery);
                        return;
                    }
                    invalidatePairIfRejected(app, expected, device, recovery.status);
                }
                if (!current(app, expected, device, true)) return;
                JSONObject body = tokenBody(fcmToken).put("code", invite)
                        .put("installationId", device.id).put("installationSecret", device.secret);
                Response response = request(device, "POST", "/v1/pair", body, false);
                if (response.is(201, "paired") && device.id.equals(response.body.optString("installationId"))) {
                    finishPair(app, expected, device, fcmToken, callback);
                } else fail(app, expected, callback, response);
            } catch (IOException | JSONException | RuntimeException unavailable) {
                deliver(app, expected, callback, false,
                        "연결을 확인하지 못했어요. 같은 코드로 다시 시도할 수 있어요.", true);
            } finally {
                // Even an interrupted response may have registered the device before local opt-out.
                removeIfAbandoned(app, device);
            }
        });
    }

    public static void test(Context context, Callback callback) {
        Context app = context.getApplicationContext();
        final Credentials device;
        final long expected;
        synchronized (LOCK) {
            expected = generation;
            String problem = prerequisite(app);
            device = credentials(app);
            if (problem == null && (device == null || !preferences(app).getBoolean("paired", false)))
                problem = "먼저 이 기기를 알림 서버에 연결해 주세요.";
            if (problem != null) {
                deliver(app, expected, callback, false, problem, false);
                return;
            }
        }
        submit(app, expected, callback, () -> {
            if (!current(app, expected, device, true)) return;
            try {
                Response response = request(device, "POST", "/test", new JSONObject(), true);
                String event = response.body.optString("eventId", "");
                if (response.is(202, "queued") && event.matches("[A-Za-z0-9_-]{1,128}")) {
                    deliver(app, expected, callback, true,
                            "시험 알림을 요청했어요. 알림이 도착하는지 확인해 주세요.", true);
                } else {
                    invalidatePairIfRejected(app, expected, device, response.status);
                    fail(app, expected, callback, response);
                }
            } catch (IOException | RuntimeException unavailable) {
                deliver(app, expected, callback, false, "시험 알림을 요청하지 못했어요. 네트워크를 확인해 주세요.", true);
            }
        });
    }

    /** Local disconnection is immediate; private cleanup credentials survive a failed remote deletion. */
    public static void disconnect(Context context, Callback callback) {
        Context app = context.getApplicationContext();
        final Credentials old;
        final long expected;
        synchronized (LOCK) {
            expected = ++generation;
            old = storedCredentials(app);
            if (old == null) preferences(app).edit().clear().commit();
            else preferences(app).edit().putBoolean("paired", false).putBoolean("cleanup_pending", true).commit();
        }
        if (old == null) {
            deliver(app, expected, callback, true, "이 기기의 알림 서버 연결을 해제했어요.", false);
            return;
        }
        try {
            WORK.execute(() -> {
                boolean confirmed = removeRemote(old);
                synchronized (LOCK) {
                    if (confirmed && generation == expected && old.same(storedCredentials(app)))
                        preferences(app).edit().clear().commit();
                }
                deliver(app, expected, callback, true, confirmed
                        ? "이 기기의 알림 서버 연결을 해제했어요."
                        : "기기에서 연결을 해제했어요. 서버의 연결 해제는 확인하지 못했어요.", false);
            });
        } catch (RejectedExecutionException busy) {
            deliver(app, expected, callback, true,
                    "기기에서 연결을 해제했어요. 서버의 연결 해제는 확인하지 못했어요.", false);
        }
    }

    /** Service entry point; a rotated FCM token never creates a new pairing by itself. */
    public static void refreshToken(Context context, String token) {
        Context app = context.getApplicationContext();
        final Credentials device;
        final long expected;
        synchronized (LOCK) {
            if (!validToken(token) || !isPaired(app)) return;
            expected = generation;
            device = credentials(app);
        }
        submit(app, expected, null, () -> {
            if (!current(app, expected, device, true)) return;
            try {
                Response response = request(device, "PUT", "/token", tokenBody(token), true);
                invalidatePairIfRejected(app, expected, device, response.status);
            } catch (IOException | JSONException | RuntimeException unavailable) {
                // Retry is possible at the next explicit token synchronization; credentials remain private.
            }
        });
    }

    private static void finishPair(Context app, long expected, Credentials device, String sentToken, Callback callback) {
        boolean accepted;
        synchronized (LOCK) {
            accepted = current(app, expected, device, true);
            if (accepted) preferences(app).edit().putBoolean("paired", true).apply();
        }
        if (accepted) {
            String latest = PushClient.cachedToken(app);
            if (validToken(latest) && !latest.equals(sentToken)) refreshToken(app, latest);
            deliver(app, expected, callback, true, "이 기기를 알림 서버에 연결했어요.", true);
        }
    }

    private static void removeIfAbandoned(Context app, Credentials device) {
        boolean remove;
        synchronized (LOCK) {
            remove = !allowed(app) || !device.same(credentials(app))
                    || preferences(app).getBoolean("cleanup_pending", false);
        }
        // A newer retry using the same credentials owns this device and must not be deleted.
        if (remove) removeRemote(device);
    }

    private static void invalidatePairIfRejected(Context app, long expected, Credentials device, int status) {
        // 410 only expires the FCM token; the installation can still authenticate a token update.
        if (status != 401 && status != 404) return;
        synchronized (LOCK) {
            if (current(app, expected, device, false)) preferences(app).edit().putBoolean("paired", false).apply();
        }
    }

    private static boolean removeRemote(Credentials device) {
        try {
            Response response = request(device, "DELETE", "", null, true);
            return response.is(200, "unpaired") || response.status == 401 || response.status == 404;
        } catch (IOException | RuntimeException unavailable) { return false; }
    }

    private static String prerequisite(Context app) {
        if (!PushClient.isEnabled(app)) return "푸시 알림을 먼저 켜 주세요.";
        if (!PushNotifications.hasPermission(app)) return "Android 알림 권한을 먼저 허용해 주세요.";
        if (endpoint(app) == null) return "이 빌드에는 알림 서버 연결 설정이 없어요.";
        return null;
    }

    private static boolean allowed(Context app) {
        return PushClient.isEnabled(app) && PushNotifications.hasPermission(app);
    }

    private static boolean current(Context app, long expected, Credentials device, boolean requireConsent) {
        synchronized (LOCK) {
            return generation == expected && (!requireConsent || allowed(app)) && device.same(credentials(app));
        }
    }

    private static void submit(Context app, long expected, Callback callback, Runnable action) {
        try { WORK.execute(action); }
        catch (RejectedExecutionException busy) {
            deliver(app, expected, callback, false, "앞선 연결 작업이 끝난 뒤 다시 시도해 주세요.", true);
        }
    }

    private static void deliver(Context app, long expected, Callback callback, boolean success,
            String message, boolean requireConsent) {
        if (callback == null) return;
        new Handler(Looper.getMainLooper()).post(() -> {
            synchronized (LOCK) {
                if (generation != expected || (requireConsent && !allowed(app))) return;
                if (success) callback.onSuccess(message);
                else callback.onError(message);
            }
        });
    }

    private static void fail(Context app, long expected, Callback callback, Response response) {
        String message;
        if (response.status == 401 && "invalid_pairing_code".equals(response.body.optString("error")))
            message = "연결 코드가 만료되었거나 이미 사용됐어요. 새 코드를 받아 주세요.";
        else if (response.status == 410)
            message = "이 기기의 푸시 연결 정보가 만료됐어요. 푸시 연결 정보를 다시 받아 주세요.";
        else if (response.status == 401 || response.status == 404)
            message = "서버 연결을 확인하지 못했어요. 새 연결 코드로 다시 연결해 주세요.";
        else if (response.status == 409)
            message = "이 기기의 기존 연결과 맞지 않아요. 연결 해제 후 새 코드로 다시 연결해 주세요.";
        else if (response.status == 429)
            message = "요청이 많아요. 잠시 기다린 뒤 다시 시도해 주세요.";
        else if (response.status == 502 || response.status == 503)
            message = "알림 서버가 아직 준비되지 않았거나 잠시 응답하지 않아요.";
        else message = "알림 서버의 응답을 확인하지 못했어요. 다시 시도해 주세요.";
        deliver(app, expected, callback, false, message, true);
    }

    private static JSONObject tokenBody(String token) throws JSONException {
        return new JSONObject().put("fcmToken", token);
    }

    private static boolean validToken(String value) {
        return value != null && value.length() >= 32 && value.length() <= 4096
                && value.matches("[A-Za-z0-9_:.-]+");
    }

    private static String random(int count) {
        byte[] bytes = new byte[count];
        RANDOM.nextBytes(bytes);
        return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static SharedPreferences preferences(Context app) {
        return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static Credentials credentials(Context app) {
        Credentials stored = storedCredentials(app);
        return stored != null && stored.endpoint.equals(endpoint(app)) ? stored : null;
    }

    private static Credentials storedCredentials(Context app) {
        SharedPreferences prefs = preferences(app);
        String server = validatedEndpoint(prefs.getString("endpoint", ""));
        String id = prefs.getString("installation_id", "");
        String secret = prefs.getString("installation_secret", "");
        if (server == null || !id.matches("[A-Za-z0-9_-]{16,80}")
                || !secret.matches("[A-Za-z0-9_-]{43,128}")) return null;
        return new Credentials(server, id, secret);
    }

    private static String endpoint(Context app) {
        int id = app.getResources().getIdentifier("moa_push_endpoint", "string", app.getPackageName());
        if (id == 0) return null;
        return validatedEndpoint(app.getString(id));
    }

    private static String validatedEndpoint(String value) {
        if (value == null || value.length() > 280) return null;
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            String path = uri.getRawPath();
            if (!"https".equals(uri.getScheme()) || host == null || host.length() > 253
                    || !host.matches("(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+workers\\.dev")
                    || uri.getRawUserInfo() != null || uri.getPort() != -1
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !(path.isEmpty() || "/".equals(path))) return null;
            return "https://" + host;
        } catch (RuntimeException malformed) { return null; }
    }

    private static Response request(Credentials device, String method, String suffix,
            JSONObject body, boolean authenticated) throws IOException {
        String path = authenticated ? "/v1/devices/" + device.id + suffix : suffix;
        HttpURLConnection connection = (HttpURLConnection) new URL(device.endpoint + path).openConnection();
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(10000);
            connection.setRequestMethod(method);
            connection.setRequestProperty("Accept", "application/json");
            if (authenticated) connection.setRequestProperty("Authorization", "Bearer " + device.secret);
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                if (bytes.length > MAX_BYTES) throw new IOException("Request too large");
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            JSONObject json = new JSONObject();
            if (stream != null) {
                try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[2048];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        if (output.size() + read > MAX_BYTES) throw new IOException("Response too large");
                        output.write(buffer, 0, read);
                    }
                    if (output.size() != 0) {
                        try { json = new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8)); }
                        catch (JSONException malformed) { throw new IOException("Invalid response"); }
                    }
                }
            }
            return new Response(status, json);
        } finally { connection.disconnect(); }
    }

    private static final class Credentials {
        final String endpoint;
        final String id;
        final String secret;
        Credentials(String endpoint, String id, String secret) {
            this.endpoint = endpoint;
            this.id = id;
            this.secret = secret;
        }
        boolean same(Credentials other) {
            return other != null && endpoint.equals(other.endpoint) && id.equals(other.id) && secret.equals(other.secret);
        }
    }

    private static final class Response {
        final int status;
        final JSONObject body;
        Response(int status, JSONObject body) { this.status = status; this.body = body; }
        boolean is(int expected, String field) { return status == expected && Boolean.TRUE.equals(body.opt(field)); }
    }
}
