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
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.json.JSONException;
import org.json.JSONArray;
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
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ArrayList<ConnectionWaiter> CONNECTION_WAITERS = new ArrayList<>();
    private static final long CONNECTION_WAIT_MS = 90_000;
    private static final String[] REGISTRATION_FIELDS = {"registration_id", "registration_token_hash",
            "registration_challenge", "registration_expires_at", "registration_phase", "registration_terminal",
            "registration_error", "registration_failures", "registration_attempts", "registration_quota_uncounted_id"};

    static { WORK.allowCoreThreadTimeOut(true); }

    public interface Callback {
        void onSuccess(String message);
        void onError(String message);
    }

    public interface WatchesCallback {
        void onSuccess(List<Watch> watches);
        void onError(String message);
    }

    public static final class Watch {
        public final String watchId, url, status;
        public final Integer baselineCount;
        public final long lastChecked, expiresAt;
        private Watch(String watchId, String url, Integer baselineCount, long lastChecked, String status, long expiresAt) {
            this.watchId = watchId; this.url = url; this.baselineCount = baselineCount;
            this.lastChecked = lastChecked; this.status = status; this.expiresAt = expiresAt;
        }
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

    /** Unix seconds; zero means no current server-budget pause is known. */
    public static long watchResumeAt(Context context) {
        try {
            long value = preferences(context.getApplicationContext()).getLong("watch_resume_at", 0);
            return value > System.currentTimeMillis() / 1000 ? value : 0;
        } catch (ClassCastException malformed) { return 0; }
    }

    /** Public opt-in flow. A challenge is never returned over HTTP or entered by the user. */
    public static void requestConnection(Context context) {
        Context app = context.getApplicationContext();
        if (endpoint(app) == null) return;
        synchronized (LOCK) {
            if (!preferences(app).getBoolean("registration_requested", false)) generation++;
            preferences(app).edit().putBoolean("registration_requested", true).commit();
        }
    }

    public static void connect(Context context, String token, Callback callback) {
        Context app = context.getApplicationContext();
        String problem;
        synchronized (LOCK) {
            problem = prerequisite(app);
            if (problem == null && (!validToken(token) || !token.equals(PushClient.cachedToken(app))))
                problem = "현재 푸시 연결 정보를 다시 받아 주세요.";
            Credentials stored = storedCredentials(app);
            if (problem == null && stored != null && !stored.endpoint.equals(endpoint(app)))
                problem = "이전 알림 서버 연결을 먼저 해제한 뒤 다시 연결해 주세요.";
            if (problem == null) {
                if (!preferences(app).getBoolean("registration_requested", false)) generation++;
                Credentials device = stored == null ? new Credentials(endpoint(app), random(16), random(32)) : stored;
                SharedPreferences.Editor edit = preferences(app).edit().putString("endpoint", device.endpoint)
                        .putString("installation_id", device.id).putString("installation_secret", device.secret)
                        .putBoolean("registration_requested", true);
                if (preferences(app).getBoolean("registration_terminal", false)) clearRegistration(edit);
                if (!edit.commit()) problem = "연결 정보를 저장하지 못했어요. 저장 공간을 확인해 주세요.";
            }
        }
        if (problem != null) { String message = problem; MAIN.post(() -> { if (callback != null) callback.onError(message); }); return; }
        observeConnection(app, callback);
        resumeRegistration(app);
    }

    /** Reattach a foreground observer without creating a new attempt on rotation or process restart. */
    public static void observeConnection(Context context, Callback callback) {
        if (callback == null) return;
        Context app = context.getApplicationContext();
        ConnectionWaiter waiter;
        synchronized (LOCK) { waiter = new ConnectionWaiter(app, generation, callback); CONNECTION_WAITERS.add(waiter); }
        MAIN.post(waiter);
    }

    public static void cancelConnectionObservers() {
        synchronized (LOCK) {
            for (ConnectionWaiter waiter : CONNECTION_WAITERS) { MAIN.removeCallbacks(waiter); waiter.callback = null; }
            CONNECTION_WAITERS.clear();
        }
    }

    public static boolean isConnecting(Context context) {
        Context app = context.getApplicationContext();
        synchronized (LOCK) {
            return allowed(app) && preferences(app).getBoolean("registration_requested", false)
                    && !preferences(app).getBoolean("registration_terminal", false) && !connectionReady(app);
        }
    }

    public static boolean isConnectionReady(Context context) {
        synchronized (LOCK) { return connectionReady(context.getApplicationContext()); }
    }

    static long registrationDelayMillis(Context context) {
        try {
            long seconds = preferences(context.getApplicationContext()).getLong("registration_retry_at", 0) - System.currentTimeMillis() / 1000;
            return seconds > 0 ? Math.min(86400, seconds) * 1000 : 0;
        } catch (ClassCastException malformed) { return 0; }
    }

    public static String connectionStatus(Context context) {
        Context app = context.getApplicationContext();
        synchronized (LOCK) {
            if (connectionReady(app)) return "푸시 연결 완료";
            if (!allowed(app)) return "알림 꺼짐";
            if (registrationDelayMillis(app) > 0) {
                long retry = preferences(app).getLong("registration_retry_at", 0);
                String time = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                        .format(new java.util.Date(retry * 1000));
                return "서버 요청이 많아 " + time + " 이후 자동으로 다시 연결해요.";
            }
            if (preferences(app).getBoolean("registration_terminal", false))
                return preferences(app).getString("registration_error", "자동 연결을 다시 시도해 주세요.");
            if (preferences(app).getBoolean("registration_requested", false)) return "이 기기의 알림 수신을 확인하고 있어요";
            return "기기 연결 대기";
        }
    }

    /** Existing paired users keep their installation and subscriptions. No first opt-in is inferred. */
    public static void resumeRegistration(Context context) {
        Context app = context.getApplicationContext();
        boolean needsCredentials;
        synchronized (LOCK) {
            if (!allowed(app)) return;
            if (!preferences(app).getBoolean("registration_requested", false) && !preferences(app).getBoolean("paired", false)) return;
            needsCredentials = credentials(app) == null;
            preferences(app).edit().putBoolean("registration_requested", true).apply();
            if (!needsCredentials && !pushWorkNeeded(app)) return;
        }
        if (needsCredentials) {
            String token = PushClient.cachedToken(app);
            if (validToken(token)) connect(app, token, null);
            return;
        }
        PushRegistrationJobService.schedule(app, 0);
    }

    /** Fast FCM receiver path: validate the previously persisted local attempt, then save proof only. */
    public static boolean acceptRegistrationMessage(Context context, Map<String, String> data) {
        if (data == null || data.size() != 5 || !"registration".equals(data.get("kind"))) return false;
        String rid = data.get("registration_id"), id = data.get("installation_id");
        String challenge = data.get("challenge"), hash = data.get("token_hash");
        if (!validRegistrationId(rid) || id == null || !id.matches("[A-Za-z0-9_-]{16,80}")
                || challenge == null || !challenge.matches("[A-Za-z0-9_-]{43,128}")
                || hash == null || !hash.matches("[A-Za-z0-9_-]{43}")) return false;
        Context app = context.getApplicationContext();
        synchronized (LOCK) {
            Credentials device = credentials(app);
            SharedPreferences prefs = preferences(app);
            if (!allowed(app) || device == null || !device.id.equals(id)
                    || !prefs.getBoolean("registration_requested", false) || prefs.getBoolean("cleanup_pending", false)
                    || !rid.equals(prefs.getString("registration_id", ""))
                    || !hash.equals(prefs.getString("registration_token_hash", ""))
                    || !hash.equals(tokenHash(PushClient.cachedToken(app)))) return false;
            if (!challenge.equals(prefs.getString("registration_challenge", ""))
                    && !prefs.edit().putString("registration_challenge", challenge).putString("registration_phase", "proof")
                    .putBoolean("registration_terminal", false).commit()) return false;
        }
        // The job owns the HTTP lifetime; no thread or network call escapes this FCM callback.
        PushRegistrationJobService.schedule(app, 0);
        return true;
    }

    interface RegistrationJobCallback { void finished(boolean retry, long delayMillis); }

    static boolean registrationWorkNeeded(Context app) {
        synchronized (LOCK) {
            return allowed(app) && credentials(app) != null && preferences(app).getBoolean("registration_requested", false)
                    && !preferences(app).getBoolean("registration_terminal", false) && !connectionReady(app);
        }
    }

    static boolean pushWorkNeeded(Context app) {
        synchronized (LOCK) {
            return registrationWorkNeeded(app) || (allowed(app) && connectionReady(app)
                    && OwnPostWatchQueue.hasPending(app));
        }
    }

    static long pushWorkDelayMillis(Context app) {
        synchronized (LOCK) {
            return connectionReady(app) ? OwnPostWatchQueue.delayMillis(app) : registrationDelayMillis(app);
        }
    }

    static void runRegistrationJob(Context context, RegistrationJobCallback callback) {
        Context app = context.getApplicationContext();
        try {
            WORK.execute(() -> {
                long delay = 0;
                try {
                    delay = recoverExpiredToken(app);
                    if (delay == 0) {
                        delay = registrationStep(app);
                        if (isConnectionReady(app) && OwnPostWatchQueue.hasPending(app)) delay = drainOwnPostWatch(app);
                    }
                }
                catch (IOException | JSONException | RuntimeException unavailable) {
                    delay = registrationFailure(app, "연결 확인이 지연되고 있어요. 네트워크를 확인해 주세요.", 30_000, false);
                }
                final long next = delay;
                MAIN.post(() -> callback.finished(false, next));
            });
        } catch (RejectedExecutionException busy) { MAIN.post(() -> callback.finished(true, 0)); }
    }

    /** Preserve installation credentials and the outbox while renewing only a rejected FCM token. */
    private static long recoverExpiredToken(Context app) {
        final String rejectedHash, snapshot;
        final Credentials device;
        final long expected;
        synchronized (LOCK) {
            if (!allowed(app)) return 0;
            rejectedHash = preferences(app).getString("expired_token_hash", "");
            if (rejectedHash.isEmpty()) return 0;
            snapshot = PushClient.cachedToken(app); device = credentials(app); expected = generation;
            if (device == null) return 0;
        }
        String fresh = !snapshot.isEmpty() && !rejectedHash.equals(tokenHash(snapshot))
                ? snapshot : PushClient.refreshExpiredToken(app, snapshot);
        synchronized (LOCK) {
            if (!current(app, expected, device, true)) return 0;
            if (!validToken(fresh) || rejectedHash.equals(tokenHash(fresh))) return 30_000;
            SharedPreferences.Editor edit = preferences(app).edit(); clearRegistration(edit);
            return edit.remove("expired_token_hash").putBoolean("registration_requested", true).commit() ? 0 : 30_000;
        }
    }

    /** Runs inside the platform-owned job after this exact FCM token has been confirmed. */
    private static long drainOwnPostWatch(Context app) {
        final Credentials device;
        final long expected;
        final OwnPostWatchQueue.Entry entry;
        final String sentTokenHash;
        synchronized (LOCK) {
            if (!connectionReady(app)) return 0;
            expected = generation;
            device = credentials(app);
            entry = OwnPostWatchQueue.nextDue(app);
            sentTokenHash = tokenHash(PushClient.cachedToken(app));
        }
        if (entry == null) return OwnPostWatchQueue.delayMillis(app);
        try {
            if (!current(app, expected, device, true) || !isConnectionReady(app)
                    || !OwnPostWatchQueue.current(app, entry)) return 0;
            JSONObject publication = new JSONObject().put("url", entry.url).put("newPost", true).put("publishedAt", entry.createdAt / 1000);
            if (!entry.notifyExisting) publication.put("notifyExisting", false);
            Response response = request(device, "PUT", "/watches", publication, true);
            synchronized (LOCK) {
                // An opt-out, account cleanup or newer outbox entry owns the state now.
                if (!current(app, expected, device, true) || !OwnPostWatchQueue.current(app, entry)) return 0;
                if (response.status == 200) {
                    if (!Boolean.TRUE.equals(response.body.opt("expired"))) {
                        Watch accepted = parseWatch(response.body.optJSONObject("watch"));
                        if (!entry.url.equals(accepted.url)) throw new IOException("Unexpected watch");
                    }
                    if (!OwnPostWatchQueue.complete(app, entry)) return 60_000;
                } else {
                    if (response.status == 401) {
                        invalidatePairIfRejected(app, expected, device, response.status);
                        preferences(app).edit().putBoolean("registration_requested", true).commit();
                    }
                    if (response.status == 410 && "device_token_expired".equals(response.body.optString("error"))
                            && sentTokenHash.equals(tokenHash(PushClient.cachedToken(app)))) {
                        SharedPreferences.Editor edit = preferences(app).edit(); clearRegistration(edit);
                        edit.remove("registered_token_hash").putString("expired_token_hash", sentTokenHash)
                                .putBoolean("registration_requested", true).commit();
                    }
                    long delay = response.status == 429 && response.retryAfterSeconds > 0
                            ? Math.min(86400, response.retryAfterSeconds) * 1000
                            : response.status == 409 ? 3_600_000 : 0;
                    if (response.status == 429) OwnPostWatchQueue.pause(app, delay > 0 ? delay : 60_000);
                    OwnPostWatchQueue.retry(app, entry, delay);
                }
            }
        } catch (IOException | JSONException | RuntimeException unavailable) {
            synchronized (LOCK) {
                if (current(app, expected, device, true)) OwnPostWatchQueue.retry(app, entry, 0);
            }
        }
        if (registrationWorkNeeded(app)) return Math.max(1_000, registrationDelayMillis(app));
        return OwnPostWatchQueue.hasPending(app) ? Math.max(1_000, OwnPostWatchQueue.delayMillis(app)) : 0;
    }

    private static long registrationStep(Context app) throws IOException, JSONException {
        for (int step = 0; step < 4; step++) {
            final Credentials device;
            final Registration registration;
            final String token;
            synchronized (LOCK) {
                if (!registrationWorkNeeded(app)) return 0;
                long notBefore = registrationDelayMillis(app);
                if (notBefore > 0) return notBefore;
                preferences(app).edit().remove("registration_retry_at").apply();
                device = credentials(app);
            }
            if (preferences(app).getBoolean("cleanup_pending", false)) {
                if (!removeRemote(device)) return registrationFailure(app, "이전 알림 연결을 정리한 뒤 다시 연결할게요.", 30_000, false);
                synchronized (LOCK) {
                    if (!allowed(app) || !device.same(credentials(app))) return 0;
                    Credentials fresh = new Credentials(endpoint(app), random(16), random(32));
                    SharedPreferences.Editor edit = preferences(app).edit().clear().putString("endpoint", fresh.endpoint)
                            .putString("installation_id", fresh.id).putString("installation_secret", fresh.secret)
                            .putBoolean("registration_requested", true).putBoolean("paired", false);
                    if (!edit.commit()) return registrationFailure(app, "연결 정보를 저장하지 못했어요.", 0, true);
                }
                continue;
            }
            synchronized (LOCK) {
                if (!allowed(app) || !device.same(credentials(app))) return 0;
                token = PushClient.cachedToken(app);
                if (!validToken(token)) return registrationFailure(app, "푸시 연결 정보를 다시 받아 주세요.", 0, true);
                registration = prepareRegistration(app, token);
                if (registration == null) return 0;
            }
            try {
            Response response;
            if (!registration.challenge.isEmpty()) {
                response = requestAt(device, "POST", "/v1/registrations/" + registration.id + "/confirm",
                        new JSONObject().put("challenge", registration.challenge), true);
                if (!registrationCurrent(app, device, registration)) return registrationWorkNeeded(app) ? 1_000 : 0;
                if (response.is(200, "paired") && device.id.equals(response.body.optString("installationId"))) {
                    finishRegistration(app, device, registration); return registrationWorkNeeded(app) ? 1_000 : 0;
                }
            } else if ("created".equals(registration.phase)) {
                JSONObject body = tokenBody(token).put("registrationId", registration.id)
                        .put("installationId", device.id).put("installationSecret", device.secret);
                response = requestAt(device, "POST", "/v1/registrations", body, false);
                if (!registrationCurrent(app, device, registration)) return registrationWorkNeeded(app) ? 1_000 : 0;
                if (response.status == 200 && "confirmed".equals(response.body.optString("state"))
                        && response.is(200, "paired") && registration.id.equals(response.body.optString("registrationId"))
                        && device.id.equals(response.body.optString("installationId"))) {
                    finishRegistration(app, device, registration); return registrationWorkNeeded(app) ? 1_000 : 0;
                }
                if (response.status == 202 && "pending".equals(response.body.optString("state"))
                        && registration.id.equals(response.body.optString("registrationId"))
                        && device.id.equals(response.body.optString("installationId"))) {
                    synchronized (LOCK) {
                        if (!registrationCurrent(app, device, registration)) return 0;
                        long expires = response.body.optLong("expiresAt", 0);
                        if (expires <= 0) throw new IOException("Invalid expiry");
                        SharedPreferences prefs = preferences(app);
                        boolean received = !prefs.getString("registration_challenge", "").isEmpty();
                        if (!prefs.edit().putLong("registration_expires_at", expires)
                                .putString("registration_phase", received ? "proof" : "pending")
                                .putInt("registration_failures", 0).commit()) throw new IOException("Storage unavailable");
                        if (received) continue;
                    }
                    return 60_000;
                }
            } else {
                response = requestAt(device, "GET", "/v1/registrations/" + registration.id, null, true);
                if (!registrationCurrent(app, device, registration)) return registrationWorkNeeded(app) ? 1_000 : 0;
                if (response.status == 200 && registration.id.equals(response.body.optString("registrationId"))
                        && device.id.equals(response.body.optString("installationId"))) {
                    String state = response.body.optString("state");
                    if ("confirmed".equals(state)) {
                        finishRegistration(app, device, registration); return registrationWorkNeeded(app) ? 1_000 : 0;
                    }
                    if ("pending".equals(state)) return 60_000;
                    if ("expired".equals(state)) { expireRegistration(app, device, registration); continue; }
                    if ("failed".equals(state)) return registrationFailure(app, "알림 수신 확인을 완료하지 못했어요. 다시 연결해 주세요.", 0, true);
                }
            }
            if (!registrationCurrent(app, device, registration)) return 0;
            if (response.status == 404 || response.status == 410) { expireRegistration(app, device, registration); continue; }
            if (response.status == 409 && "registration_cancelled".equals(response.body.optString("error"))) {
                synchronized (LOCK) {
                    if (registrationCurrent(app, device, registration))
                        preferences(app).edit().putBoolean("cleanup_pending", true).putBoolean("paired", false).commit();
                }
                continue;
            }
            if (response.status == 429) return registrationQuotaPause(app, device, registration, response.retryAfterSeconds);
            if (response.status >= 500) return registrationFailure(app, "알림 서버 연결이 지연되고 있어요. 잠시 뒤 다시 시도할게요.", 30_000, false);
            return registrationFailure(app, "이 기기의 알림 연결을 확인하지 못했어요. 다시 연결해 주세요.", 0, true);
            } catch (IOException | JSONException | RuntimeException unavailable) {
                if (!registrationCurrent(app, device, registration)) return registrationWorkNeeded(app) ? 1_000 : 0;
                return registrationFailure(app, "연결 확인이 지연되고 있어요. 네트워크를 확인해 주세요.", 30_000, false);
            }
        }
        return registrationFailure(app, "연결 확인을 다시 시도해 주세요.", 0, true);
    }

    private static Registration prepareRegistration(Context app, String token) {
        SharedPreferences prefs = preferences(app);
        String hash = tokenHash(token), rid = prefs.getString("registration_id", "");
        long expires = prefs.getLong("registration_expires_at", 0), now = System.currentTimeMillis() / 1000;
        if (!validRegistrationId(rid) || !hash.equals(prefs.getString("registration_token_hash", "")) || expires <= now) {
            int attempts = prefs.getInt("registration_attempts", 0);
            if (attempts >= 3) { registrationFailure(app, "알림 확인 메시지를 받지 못했어요. 앱에서 다시 연결해 주세요.", 0, true); return null; }
            rid = UUID.randomUUID().toString();
            SharedPreferences.Editor edit = prefs.edit(); clearRegistration(edit);
            if (!edit.putString("registration_id", rid).putString("registration_token_hash", hash)
                    .putLong("registration_expires_at", now + 300).putString("registration_phase", "created")
                    .putInt("registration_attempts", attempts + 1).commit()) return null;
        }
        return new Registration(rid, hash, prefs.getString("registration_phase", "created"), prefs.getString("registration_challenge", ""));
    }

    private static void expireRegistration(Context app, Credentials device, Registration registration) {
        synchronized (LOCK) {
            if (registrationCurrent(app, device, registration)) preferences(app).edit().putLong("registration_expires_at", 0).commit();
        }
    }

    private static boolean registrationCurrent(Context app, Credentials device, Registration registration) {
        synchronized (LOCK) {
            SharedPreferences prefs = preferences(app);
            return allowed(app) && device.same(credentials(app)) && !prefs.getBoolean("cleanup_pending", false)
                    && prefs.getBoolean("registration_requested", false) && registration.id.equals(prefs.getString("registration_id", ""))
                    && registration.hash.equals(prefs.getString("registration_token_hash", ""))
                    && registration.hash.equals(tokenHash(PushClient.cachedToken(app)));
        }
    }

    private static void finishRegistration(Context app, Credentials device, Registration registration) {
        synchronized (LOCK) {
            if (!registrationCurrent(app, device, registration)) return;
            SharedPreferences.Editor edit = preferences(app).edit(); clearRegistration(edit);
            edit.putBoolean("paired", true).putBoolean("registration_requested", true)
                    .putString("registered_token_hash", registration.hash).commit();
        }
    }

    private static long registrationFailure(Context app, String message, long delay, boolean terminal) {
        synchronized (LOCK) {
            if (!allowed(app) || connectionReady(app)) return 0;
            SharedPreferences prefs = preferences(app);
            int failures = prefs.getInt("registration_failures", 0) + 1;
            boolean stopped = terminal || failures >= 5;
            prefs.edit().putInt("registration_failures", failures).putString("registration_error", message)
                    .putBoolean("registration_terminal", stopped).commit();
            return stopped ? 0 : delay;
        }
    }

    private static long registrationQuotaPause(Context app, Credentials device, Registration registration, long requestedSeconds) {
        synchronized (LOCK) {
            if (!registrationCurrent(app, device, registration)) return 0;
            long seconds = requestedSeconds > 0 ? Math.min(86400, requestedSeconds) : 60;
            SharedPreferences prefs = preferences(app);
            SharedPreferences.Editor edit = prefs.edit().putLong("registration_retry_at", System.currentTimeMillis() / 1000 + seconds)
                    .putBoolean("registration_terminal", false).putInt("registration_failures", 0).remove("registration_error");
            if (!registration.id.equals(prefs.getString("registration_quota_uncounted_id", ""))) {
                edit.putString("registration_quota_uncounted_id", registration.id)
                        .putInt("registration_attempts", Math.max(0, prefs.getInt("registration_attempts", 0) - 1));
            }
            if (!edit.commit()) return registrationFailure(app, "연결 대기 시간을 저장하지 못했어요.", 0, true);
            return seconds * 1000;
        }
    }

    private static boolean connectionReady(Context app) {
        String current = tokenHash(PushClient.cachedToken(app));
        return !current.isEmpty() && isPaired(app) && current.equals(preferences(app).getString("registered_token_hash", ""));
    }

    private static void clearRegistration(SharedPreferences.Editor edit) {
        for (String key : REGISTRATION_FIELDS) edit.remove(key);
    }

    static String tokenHash(String value) {
        if (!validToken(value)) return "";
        try { return Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static boolean validRegistrationId(String value) {
        return value != null && value.matches("[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}");
    }

    private static final class Registration {
        final String id, hash, phase, challenge;
        Registration(String id, String hash, String phase, String challenge) { this.id = id; this.hash = hash; this.phase = phase; this.challenge = challenge; }
    }

    private static final class ConnectionWaiter implements Runnable {
        final Context app; final long expected, deadline = android.os.SystemClock.elapsedRealtime() + CONNECTION_WAIT_MS;
        Callback callback;
        ConnectionWaiter(Context app, long expected, Callback callback) { this.app = app; this.expected = expected; this.callback = callback; }
        @Override public void run() {
            Callback result = null; boolean success = false; String message = "";
            synchronized (LOCK) {
                if (callback == null || generation != expected || !allowed(app)) { CONNECTION_WAITERS.remove(this); callback = null; return; }
                if (connectionReady(app)) { success = true; message = "이 기기를 알림 서버에 자동으로 연결했어요."; }
                else if (registrationDelayMillis(app) > 0) message = connectionStatus(app);
                else if (preferences(app).getBoolean("registration_terminal", false)) message = connectionStatus(app);
                else if (android.os.SystemClock.elapsedRealtime() >= deadline)
                    message = "알림 확인 메시지를 기다리고 있어요. 잠시 뒤 앱을 다시 열거나 연결을 다시 시도해 주세요.";
                if (!message.isEmpty()) { result = callback; callback = null; CONNECTION_WAITERS.remove(this); }
            }
            if (result != null) { if (success) result.onSuccess(message); else result.onError(message); }
            else MAIN.postDelayed(this, 500);
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

    /** Returns only this paired installation's explicitly selected public articles. */
    public static void listWatches(Context context, WatchesCallback callback) {
        Context app = context.getApplicationContext();
        final long expected;
        final Credentials device;
        Callback errors = new Callback() {
            @Override public void onSuccess(String ignored) { }
            @Override public void onError(String message) { if (callback != null) callback.onError(message); }
        };
        synchronized (LOCK) {
            expected = generation;
            device = watchDevice(app, expected, errors);
            if (device == null) return;
        }
        submit(app, expected, errors, () -> {
            if (!current(app, expected, device, true) || !isPaired(app)) return;
            try {
                Response response = request(device, "GET", "/watches", null, true);
                if (response.status != 200) { failWatch(app, expected, device, errors, response); return; }
                JSONArray array = response.body.optJSONArray("watches");
                if (array == null || array.length() > 20) throw new IOException("Invalid watches");
                ArrayList<Watch> watches = new ArrayList<>();
                Set<String> ids = new HashSet<>(), urls = new HashSet<>();
                for (int index = 0; index < array.length(); index++) {
                    Watch watch = parseWatch(array.optJSONObject(index));
                    if (!ids.add(watch.watchId) || !urls.add(watch.url)) throw new IOException("Duplicate watches");
                    watches.add(watch);
                }
                synchronized (LOCK) {
                    if (!current(app, expected, device, true) || !isPaired(app)) return;
                    long seconds = 0;
                    Object retry = response.body.opt("retryAfterSeconds");
                    if ("quota_paused".equals(response.body.optString("queueStatus")) && retry instanceof Number) {
                        double numeric = ((Number) retry).doubleValue();
                        if (Double.isFinite(numeric) && numeric >= 0 && numeric <= 86400 && numeric == Math.floor(numeric)) seconds = (long) numeric;
                    }
                    SharedPreferences.Editor edit = preferences(app).edit();
                    if (seconds > 0) edit.putLong("watch_resume_at", System.currentTimeMillis() / 1000 + seconds);
                    else edit.remove("watch_resume_at");
                    edit.apply();
                }
                List<Watch> result = Collections.unmodifiableList(watches);
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (callback != null && current(app, expected, device, true) && isPaired(app)) callback.onSuccess(result);
                });
            } catch (IOException | RuntimeException unavailable) {
                deliver(app, expected, errors, false, "댓글 알림 목록을 확인하지 못했어요. 잠시 후 다시 시도해 주세요.", true);
            }
        });
    }

    /** Call only after the user accepts sending this public address to the relay. */
    public static void watch(Context context, String url, Callback callback) {
        changeWatch(context, PushNotifications.canonicalWatchTarget(url), null, callback);
    }

    public static void unwatch(Context context, String watchId, Callback callback) {
        changeWatch(context, null, watchId, callback);
    }

    private static void changeWatch(Context context, String url, String watchId, Callback callback) {
        Context app = context.getApplicationContext();
        final long expected;
        final Credentials device;
        synchronized (LOCK) {
            expected = generation;
            if (url == null && !validWatchId(watchId)) {
                deliver(app, expected, callback, false, "댓글을 확인할 글이나 알림 항목을 다시 선택해 주세요.", false);
                return;
            }
            device = watchDevice(app, expected, callback);
            if (device == null) return;
        }
        submit(app, expected, callback, () -> {
            if (!current(app, expected, device, true) || !isPaired(app)) return;
            try {
                Response response = request(device, url != null ? "PUT" : "DELETE",
                        url != null ? "/watches" : "/watches/" + watchId,
                        url != null ? new JSONObject().put("url", url) : null, true);
                if (url != null && response.status == 200) {
                    Watch accepted = parseWatch(response.body.optJSONObject("watch"));
                    if (!url.equals(accepted.url)) throw new IOException("Unexpected watch");
                    deliver(app, expected, callback, true, url.contains("?comment=")
                            ? "이 댓글의 새 답글을 지금부터 3일간 확인해요. 이후에는 자동으로 멈춥니다."
                            : "이 글의 댓글 수를 지금부터 3일간 확인해요. 이후에는 자동으로 멈춥니다.", true);
                } else if (url == null && response.is(200, "removed")) {
                    deliver(app, expected, callback, true, "이 글의 댓글 알림을 해제했어요.", true);
                } else failWatch(app, expected, device, callback, response);
            } catch (IOException | JSONException | RuntimeException unavailable) {
                deliver(app, expected, callback, false, "댓글 알림 변경을 확인하지 못했어요. 목록을 새로고침해 주세요.", true);
            }
        });
    }

    private static Credentials watchDevice(Context app, long expected, Callback callback) {
        String problem = prerequisite(app);
        Credentials device = credentials(app);
        if (problem == null && !isPaired(app)) problem = "먼저 이 기기를 알림 서버에 연결해 주세요.";
        if (problem != null) { deliver(app, expected, callback, false, problem, false); return null; }
        return device;
    }

    private static Watch parseWatch(JSONObject item) throws IOException {
        if (item == null) throw new IOException("Invalid watch");
        String id = item.optString("watchId", ""), url = item.optString("url", ""), status = item.optString("status", "");
        if (!validWatchId(id) || !url.equals(PushNotifications.validatedTarget(url))
                || !status.matches("pending|active|blocked|rate_limited|unavailable|parse_error|delivery_error|token_expired"))
            throw new IOException("Invalid watch");
        Object raw = item.opt("baselineCount");
        if (!item.has("baselineCount")) throw new IOException("Missing baseline");
        Integer count = raw == JSONObject.NULL ? null : (int) wholeNumber(raw, 1000000);
        long checked = wholeNumber(item.opt("lastChecked"), 4102444800L);
        long expires = wholeNumber(item.opt("expiresAt"), 4102444800L);
        if (expires == 0) throw new IOException("Invalid expiration");
        return new Watch(id, url, count, checked, status, expires);
    }

    private static long wholeNumber(Object value, long maximum) throws IOException {
        if (!(value instanceof Number)) throw new IOException("Invalid number");
        double number = ((Number) value).doubleValue();
        if (!Double.isFinite(number) || number < 0 || number > maximum || number != Math.floor(number))
            throw new IOException("Invalid number");
        return ((Number) value).longValue();
    }

    private static boolean validWatchId(String value) {
        return value != null && value.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    }

    private static void failWatch(Context app, long expected, Credentials device, Callback callback, Response response) {
        // 404 can mean an older relay without this feature, not an invalid pairing.
        if (response.status == 401) invalidatePairIfRejected(app, expected, device, response.status);
        if (response.status == 404)
            deliver(app, expected, callback, false, "댓글 확인 서버의 업데이트가 아직 적용되지 않았어요.", true);
        else fail(app, expected, callback, response);
    }

    /** Local disconnection is immediate; private cleanup credentials survive a failed remote deletion. */
    public static void disconnect(Context context, Callback callback) {
        Context app = context.getApplicationContext();
        OwnPostWatchQueue.clear(app);
        PushRegistrationJobService.cancel(app);
        cancelConnectionObservers();
        final Credentials old;
        final long expected;
        synchronized (LOCK) {
            expected = ++generation;
            old = storedCredentials(app);
            if (old == null) preferences(app).edit().clear().commit();
            else {
                SharedPreferences.Editor edit = preferences(app).edit(); clearRegistration(edit);
                edit.putBoolean("paired", false).putBoolean("cleanup_pending", true)
                        .putBoolean("registration_requested", false).remove("registered_token_hash").commit();
            }
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

    /** FCM callback entry: persist intent and let JobScheduler own every token synchronization. */
    public static void refreshToken(Context context, String token) {
        Context app = context.getApplicationContext();
        synchronized (LOCK) {
            if (!validToken(token) || !token.equals(PushClient.cachedToken(app)) || !allowed(app)) return;
            SharedPreferences prefs = preferences(app);
            if (!prefs.getBoolean("paired", false) && !prefs.getBoolean("registration_requested", false)) return;
            SharedPreferences.Editor edit = prefs.edit().putBoolean("registration_requested", true);
            if (!tokenHash(token).equals(prefs.getString("registration_token_hash", ""))
                    && !tokenHash(token).equals(prefs.getString("registered_token_hash", ""))) clearRegistration(edit);
            edit.commit();
        }
        resumeRegistration(app);
    }
    private static void finishPair(Context app, long expected, Credentials device, String sentToken, Callback callback) {
        boolean accepted;
        synchronized (LOCK) {
            accepted = current(app, expected, device, true);
            if (accepted) preferences(app).edit().putBoolean("paired", true).putBoolean("registration_requested", true)
                    .putString("registered_token_hash", tokenHash(sentToken)).apply();
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
            message = "서버 연결을 확인하지 못했어요. 알림 설정에서 자동 연결을 다시 시도해 주세요.";
        else if (response.status == 409)
            message = "이 기기의 연결 정보를 다시 확인해야 해요. 알림 설정에서 자동 연결을 다시 시도해 주세요.";
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
        return requestAt(device, method, path, body, authenticated);
    }

    private static Response requestAt(Credentials device, String method, String path,
            JSONObject body, boolean authenticated) throws IOException {
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
            long retryAfter = 0;
            try {
                String header = connection.getHeaderField("Retry-After");
                if (header != null && header.matches("[0-9]{1,6}")) retryAfter = Long.parseLong(header);
            } catch (RuntimeException ignored) { }
            return new Response(status, json, retryAfter);
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
        final long retryAfterSeconds;
        Response(int status, JSONObject body, long retryAfterSeconds) { this.status = status; this.body = body; this.retryAfterSeconds = retryAfterSeconds; }
        boolean is(int expected, String field) { return status == expected && Boolean.TRUE.equals(body.opt(field)); }
    }
}
