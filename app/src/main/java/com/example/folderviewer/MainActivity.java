package com.example.folderviewer;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Build;
import android.os.Environment;
import android.Manifest;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Looper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public class MainActivity extends Activity {
    private LinearLayout root;
    private TextView pathView;
    private TextView statusView;
    private ListView listView;
    private Button permissionButton;
    private Button connectButton;
    private Button requestsButton;
    private LocationManager locationManager;
    private static final int LOCATION_PERMISSION_REQUEST = 7401;
    private File currentDir;
    private final List<File> shownFiles = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private SharedPreferences prefs;

    private static final String SUPABASE_URL = "https://rvtqkldsapdtiocjygvc.supabase.co";
    private static final String SUPABASE_PUBLISHABLE_KEY = "sb_publishable_OA3eukLqTY8BrL3lf5trwQ_apXWqXh-";
    private static final String PREFS = "folder_viewer_device";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_DEVICE_TOKEN = "device_token";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        buildUi();
        if (hasAllFilesAccess()) openDirectory(Environment.getExternalStorageDirectory());
        else showPermissionNeeded();
    }

    private void connectOrPing() {
        if (prefs.contains(KEY_DEVICE_ID) && prefs.contains(KEY_DEVICE_TOKEN)) {
            pingRegisteredDevice();
        } else {
            askEnrollmentCodeAndRegister();
        }
    }

    private void askEnrollmentCodeAndRegister() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("DEVICE_ENROLLMENT_CODE-এর Value");
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        new AlertDialog.Builder(this)
                .setTitle("প্রথমবার ডিভাইস নিবন্ধন")
                .setMessage("Supabase Secrets-এ DEVICE_ENROLLMENT_CODE-এর যে Value সেট করেছেন, সেটি লিখুন। এটি কাউকে পাঠাবেন না।")
                .setView(input)
                .setNegativeButton("বাতিল", (d, w) -> { })
                .setPositiveButton("নিবন্ধন", (d, w) -> {
                    String enrollmentCode = input.getText().toString();
                    if (enrollmentCode.trim().length() < 24) {
                        statusView.setText("Enrollment code কমপক্ষে ২৪ অক্ষরের হতে হবে।");
                        return;
                    }
                    String token = UUID.randomUUID().toString().replace("-", "")
                            + UUID.randomUUID().toString().replace("-", "");
                    setBusy(true, "ডিভাইস নিবন্ধন হচ্ছে...");
                    new Thread(() -> {
                        try {
                            JSONObject body = new JSONObject();
                            body.put("action", "register");
                            body.put("device_name", Build.MANUFACTURER + " " + Build.MODEL + " - Folder Viewer");
                            body.put("device_token", token);
                            body.put("enrollment_code", enrollmentCode);
                            JSONObject response = postToGateway(body);
                            if (response.optBoolean("ok") && response.has("device_id")) {
                                long id = response.getLong("device_id");
                                prefs.edit().putLong(KEY_DEVICE_ID, id).putString(KEY_DEVICE_TOKEN, token).apply();
                                runOnUiThread(() -> {
                                    setBusy(false, "ডিভাইস নিবন্ধন সফল (ID: " + id + ")। এখন Ping করতে আবার বাটন চাপুন।");
                                    Toast.makeText(this, "নিবন্ধন সফল", Toast.LENGTH_LONG).show();
                                });
                            } else {
                                String error = response.optString("error", "অজানা ত্রুটি");
                                runOnUiThread(() -> setBusy(false, "নিবন্ধন ব্যর্থ: " + error));
                            }
                        } catch (Exception e) {
                            runOnUiThread(() -> setBusy(false, "নিবন্ধন ব্যর্থ। Function URL, API key ও নেটওয়ার্ক পরীক্ষা করুন।"));
                        }
                    }).start();
                }).show();
    }

    private void pingRegisteredDevice() {
        long id = prefs.getLong(KEY_DEVICE_ID, -1);
        String token = prefs.getString(KEY_DEVICE_TOKEN, "");
        if (id < 1 || token.isEmpty()) {
            prefs.edit().remove(KEY_DEVICE_ID).remove(KEY_DEVICE_TOKEN).apply();
            askEnrollmentCodeAndRegister();
            return;
        }
        setBusy(true, "সার্ভারে Ping পাঠানো হচ্ছে...");
        new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("action", "ping");
                body.put("device_id", id);
                body.put("device_token", token);
                JSONObject response = postToGateway(body);
                if (response.optBoolean("ok")) {
                    String name = response.optString("device_name", "Folder Viewer");
                    runOnUiThread(() -> setBusy(false, "Ping সফল! " + name + " (ID: " + id + ")"));
                } else {
                    String error = response.optString("error", "অজানা ত্রুটি");
                    runOnUiThread(() -> setBusy(false, "Ping ব্যর্থ: " + error));
                }
            } catch (Exception e) {
                runOnUiThread(() -> setBusy(false, "Ping ব্যর্থ। ইন্টারনেট বা Edge Function সেটিংস পরীক্ষা করুন।"));
            }
        }).start();
    }


    private void checkServerRequests() {
        if (!prefs.contains(KEY_DEVICE_ID) || !prefs.contains(KEY_DEVICE_TOKEN)) {
            statusView.setText("আগে সার্ভার Connect / Ping করে ডিভাইস নিবন্ধন করুন।");
            return;
        }
        long id = prefs.getLong(KEY_DEVICE_ID, -1);
        String token = prefs.getString(KEY_DEVICE_TOKEN, "");
        setBusy(true, "সার্ভারের অনুরোধ দেখা হচ্ছে...");
        new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("action", "poll_requests");
                body.put("device_id", id);
                body.put("device_token", token);
                JSONObject response = postToGateway(body);
                if (!response.optBoolean("ok")) {
                    String error = response.optString("error", "অজানা ত্রুটি");
                    runOnUiThread(() -> setBusy(false, "অনুরোধ পাওয়া যায়নি: " + error));
                    return;
                }
                org.json.JSONArray requests = response.optJSONArray("requests");
                runOnUiThread(() -> {
                    setBusy(false, "সার্ভার অনুরোধ পরীক্ষা সম্পন্ন।");
                    if (requests == null || requests.length() == 0) {
                        new AlertDialog.Builder(this)
                            .setTitle("সার্ভারের অনুরোধ")
                            .setMessage("এখন কোনো অপেক্ষমাণ অনুরোধ নেই।")
                            .setPositiveButton("ঠিক আছে", null).show();
                    } else {
                        showRequestAt(requests, 0);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> setBusy(false, "অনুরোধ পরীক্ষা ব্যর্থ। নেটওয়ার্ক/Function দেখুন।"));
            }
        }).start();
    }

    private void showRequestAt(org.json.JSONArray requests, int index) {
        if (index >= requests.length()) {
            statusView.setText("সার্ভারের সব অনুরোধ দেখা হয়েছে।");
            return;
        }
        JSONObject request = requests.optJSONObject(index);
        if (request == null) { showRequestAt(requests, index + 1); return; }
        long requestId = request.optLong("id", -1);
        String action = request.optString("action", "");
        JSONObject payload = request.optJSONObject("request_payload");
        String requestedPath = payload == null ? "" : payload.optString("path", "");
        if ("get_location".equals(action)) {
            new AlertDialog.Builder(this)
                .setTitle("লোকেশন অনুরোধ")
                .setMessage("ওয়েব প্যানেল থেকে এই ফোনের বর্তমান অবস্থান চাওয়া হয়েছে। অনুমতি দিলে একবার অবস্থান নেওয়ার চেষ্টা করে সার্ভারে পাঠানো হবে। আপনি কি চালিয়ে যেতে চান?")
                .setNegativeButton("না", (d, w) -> submitRequestResult(requestId, "rejected", resultObject("message", "Phone user denied location request"), () -> showRequestAt(requests, index + 1)))
                .setPositiveButton("অনুমতি দিন", (d, w) -> beginLocationRequest(requestId, () -> showRequestAt(requests, index + 1)))
                .setOnCancelListener(d -> showRequestAt(requests, index + 1))
                .show();
        } else if ("list_files".equals(action)) {
            new AlertDialog.Builder(this)
                .setTitle("ফাইল তালিকার অনুরোধ")
                .setMessage("সার্ভার এই ফোল্ডারের ফাইলের নাম ও আকার দেখতে চায়:\n\n"
                    + (requestedPath.isEmpty() ? "বর্তমান ফোল্ডার" : requestedPath)
                    + "\n\nতালিকা পাঠালে ফাইলের নাম ও আকার সার্ভারে যাবে। অনুমতি দেবেন?")
                .setNegativeButton("না", (d, w) -> submitRequestResult(requestId, "rejected", new JSONObject(), () -> showRequestAt(requests, index + 1)))
                .setPositiveButton("অনুমতি দিন", (d, w) -> sendRequestedFileList(requestId, requestedPath, () -> showRequestAt(requests, index + 1)))
                .setOnCancelListener(d -> showRequestAt(requests, index + 1))
                .show();
        } else if ("delete_file".equals(action)) {
            new AlertDialog.Builder(this)
                .setTitle("সার্ভার ফাইল ডিলিট করতে বলেছে")
                .setMessage("সার্ভার অনুরোধ করেছে, কিন্তু আপনার অনুমতি ছাড়া কিছু ডিলিট হবে না:\n\n"
                    + requestedPath + "\n\nএই ফাইলটি স্থায়ীভাবে মুছবেন?")
                .setNegativeButton("বাতিল / ডিলিট নয়", (d, w) -> submitRequestResult(requestId, "rejected", new JSONObject(), () -> showRequestAt(requests, index + 1)))
                .setPositiveButton("ফাইল ডিলিট করুন", (d, w) -> approveAndDeleteRequestedFile(requestId, requestedPath, () -> showRequestAt(requests, index + 1)))
                .setOnCancelListener(d -> showRequestAt(requests, index + 1))
                .show();
        } else {
            submitRequestResult(requestId, "failed", resultObject("error", "Unknown request action"), () -> showRequestAt(requests, index + 1));
        }
    }

    private void beginLocationRequest(long requestId, Runnable done) {
        if (Build.VERSION.SDK_INT >= 23 &&
            (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
             checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)) {
            pendingLocationRequestId = requestId;
            pendingLocationDone = done;
            requestPermissions(new String[] {
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION
            }, LOCATION_PERMISSION_REQUEST);
            return;
        }
        fetchOneShotLocation(requestId, done);
    }

    private long pendingLocationRequestId = -1;
    private Runnable pendingLocationDone;

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != LOCATION_PERMISSION_REQUEST) return;
        long requestId = pendingLocationRequestId;
        Runnable done = pendingLocationDone == null ? () -> {} : pendingLocationDone;
        pendingLocationRequestId = -1;
        pendingLocationDone = null;
        boolean granted = false;
        for (int result : grantResults) {
            if (result == PackageManager.PERMISSION_GRANTED) { granted = true; break; }
        }
        if (!granted) {
            submitRequestResult(requestId, "rejected", resultObject("error", "Location permission denied"), done);
            Toast.makeText(this, "লোকেশন অনুমতি দেওয়া হয়নি।", Toast.LENGTH_LONG).show();
            return;
        }
        fetchOneShotLocation(requestId, done);
    }

    private boolean hasLocationPermission() {
        return Build.VERSION.SDK_INT < 23 ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressWarnings("MissingPermission")
    private void fetchOneShotLocation(long requestId, Runnable done) {
        if (!hasLocationPermission()) {
            submitRequestResult(requestId, "rejected", resultObject("error", "Location permission missing"), done);
            return;
        }
        if (locationManager == null) {
            submitRequestResult(requestId, "failed", resultObject("error", "Location service unavailable"), done);
            return;
        }

        final boolean gpsEnabled;
        final boolean networkEnabled;
        try {
            gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
            networkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Exception e) {
            submitRequestResult(requestId, "failed", resultObject("error", "Could not check location providers"), done);
            return;
        }
        if (!gpsEnabled && !networkEnabled) {
            submitRequestResult(requestId, "failed", resultObject("error", "Location services are turned off"), done);
            Toast.makeText(this, "ফোনের Location চালু করুন, তারপর আবার অনুরোধ করুন।", Toast.LENGTH_LONG).show();
            return;
        }

        setBusy(true, "অনুমোদিত লোকেশন একবার নেওয়ার চেষ্টা হচ্ছে...");
        final android.os.Handler handler = new android.os.Handler(Looper.getMainLooper());
        final boolean[] finished = {false};
        final LocationListener listener = new LocationListener() {
            @Override public void onLocationChanged(Location location) {
                if (finished[0]) return;
                finished[0] = true;
                try { locationManager.removeUpdates(this); } catch (Exception ignored) {}
                JSONObject result = new JSONObject();
                try {
                    result.put("latitude", location.getLatitude());
                    result.put("longitude", location.getLongitude());
                    result.put("accuracy_meters", location.hasAccuracy() ? location.getAccuracy() : JSONObject.NULL);
                    result.put("provider", location.getProvider() == null ? "unknown" : location.getProvider());
                    result.put("captured_at", new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", java.util.Locale.US).format(new java.util.Date(location.getTime())));
                } catch (Exception ignored) {}
                submitRequestResult(requestId, "completed", result, () -> {
                    setBusy(false, "অনুমোদিত লোকেশন সার্ভারে পাঠানো হয়েছে।");
                    Toast.makeText(MainActivity.this, "লোকেশন পাঠানো হয়েছে।", Toast.LENGTH_LONG).show();
                    done.run();
                });
            }
            @Override public void onProviderEnabled(String provider) {}
            @Override public void onProviderDisabled(String provider) {}
            @Override public void onStatusChanged(String provider, int status, android.os.Bundle extras) {}
        };

        Runnable timeout = () -> {
            if (finished[0]) return;
            finished[0] = true;
            try { locationManager.removeUpdates(listener); } catch (Exception ignored) {}
            submitRequestResult(requestId, "failed", resultObject("error", "Location timeout; no fix received within 30 seconds"), () -> {
                setBusy(false, "৩০ সেকেন্ডে লোকেশন পাওয়া যায়নি। Location/নেটওয়ার্ক পরীক্ষা করে আবার চেষ্টা করুন।");
                done.run();
            });
        };
        try {
            if (networkEnabled) locationManager.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, listener, Looper.getMainLooper());
            if (gpsEnabled) locationManager.requestSingleUpdate(LocationManager.GPS_PROVIDER, listener, Looper.getMainLooper());
            handler.postDelayed(timeout, 30000);
        } catch (Exception e) {
            finished[0] = true;
            try { locationManager.removeUpdates(listener); } catch (Exception ignored) {}
            submitRequestResult(requestId, "failed", resultObject("error", "Unable to request location"), () -> {
                setBusy(false, "লোকেশন নেওয়া যায়নি। অনুমতি ও Location setting পরীক্ষা করুন।");
                done.run();
            });
        }
    }

    private void sendRequestedFileList(long requestId, String requestedPath, Runnable done) {
        if (!hasAllFilesAccess()) {
            Toast.makeText(this, "আগে All files access অনুমতি দিন।", Toast.LENGTH_LONG).show();
            submitRequestResult(requestId, "failed", resultObject("error", "Storage permission missing"), done);
            return;
        }
        File target = requestedPath.isEmpty() ? currentDir : new File(requestedPath);
        if (target == null || !target.isDirectory() || !target.canRead()) {
            submitRequestResult(requestId, "failed", resultObject("error", "Folder unavailable"), done);
            return;
        }
        File[] files = target.listFiles();
        org.json.JSONArray items = new org.json.JSONArray();
        if (files != null) {
            Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File f : files) {
                JSONObject item = new JSONObject();
                try {
                    item.put("name", f.getName());
                    item.put("path", f.getAbsolutePath());
                    item.put("directory", f.isDirectory());
                    if (f.isFile()) item.put("size_bytes", f.length());
                    items.put(item);
                } catch (Exception ignored) {}
            }
        }
        JSONObject result = new JSONObject();
        try {
            result.put("path", target.getAbsolutePath());
            result.put("items", items);
        } catch (Exception ignored) {}
        submitRequestResult(requestId, "completed", result, () -> {
            statusView.setText("অনুমোদিত ফাইল তালিকা সার্ভারে পাঠানো হয়েছে।");
            done.run();
        });
    }

    private void approveAndDeleteRequestedFile(long requestId, String requestedPath, Runnable done) {
        if (!hasAllFilesAccess()) {
            submitRequestResult(requestId, "failed", resultObject("error", "Storage permission missing"), done);
            return;
        }
        File target = new File(requestedPath);
        String rootPath = Environment.getExternalStorageDirectory().getAbsolutePath();
        try {
            String canonicalRoot = new File(rootPath).getCanonicalPath();
            String canonicalTarget = target.getCanonicalPath();
            if (!canonicalTarget.startsWith(canonicalRoot + File.separator) || target.isDirectory() || !target.isFile()) {
                submitRequestResult(requestId, "failed", resultObject("error", "Only a regular file inside shared storage can be deleted"), done);
                return;
            }
            new AlertDialog.Builder(this)
                .setTitle("শেষবার নিশ্চিত করুন")
                .setMessage("শুধু এই ফাইলটি মুছবে:\n\n" + canonicalTarget)
                .setNegativeButton("না", (d, w) -> submitRequestResult(requestId, "rejected", new JSONObject(), done))
                .setPositiveButton("স্থায়ীভাবে ডিলিট", (d, w) -> {
                    boolean deleted = target.delete();
                    JSONObject result = resultObject("path", canonicalTarget);
                    try { result.put("deleted", deleted); } catch (Exception ignored) {}
                    submitRequestResult(requestId, deleted ? "completed" : "failed", result, () -> {
                        if (deleted) openDirectory(currentDir);
                        done.run();
                    });
                }).show();
        } catch (Exception e) {
            submitRequestResult(requestId, "failed", resultObject("error", "Invalid file path"), done);
        }
    }

    private JSONObject resultObject(String key, String value) {
        JSONObject result = new JSONObject();
        try { result.put(key, value); } catch (Exception ignored) {}
        return result;
    }

    private void submitRequestResult(long requestId, String status, JSONObject result, Runnable done) {
        if (requestId <= 0) { done.run(); return; }
        long deviceId = prefs.getLong(KEY_DEVICE_ID, -1);
        String token = prefs.getString(KEY_DEVICE_TOKEN, "");
        new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("action", "complete_request");
                body.put("device_id", deviceId);
                body.put("device_token", token);
                body.put("request_id", requestId);
                body.put("status", status);
                body.put("result_payload", result);
                postToGateway(body);
            } catch (Exception ignored) {}
            runOnUiThread(done);
        }).start();
    }

    private JSONObject postToGateway(JSONObject body) throws Exception {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(SUPABASE_URL + "/functions/v1/device-gateway");
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
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
                byte[] chunk = new byte[4096];
                int n;
                while ((n = in.read(chunk)) != -1) buffer.write(chunk, 0, n);
                in.close();
            }
            String responseText = buffer.toString("UTF-8");
            JSONObject response = new JSONObject(responseText);
            if (code < 200 || code >= 300) {
                if (!response.has("error")) response.put("error", "HTTP " + code);
            }
            return response;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void setBusy(boolean busy, String message) {
        statusView.setText(message);
        connectButton.setEnabled(!busy);
        if (requestsButton != null) requestsButton.setEnabled(!busy);
    }

    private boolean hasAllFilesAccess() {
        return Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager();
    }

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(8));
        root.setBackgroundColor(0xFFF7F7F7);

        TextView title = new TextView(this);
        title.setText("Folder Viewer");
        title.setTextSize(28);
        title.setTextColor(0xFF202124);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView subtitle = new TextView(this);
        subtitle.setText("ফাইল ও ফোল্ডার দেখা, ফোনে নিশ্চিত করে ফাইল ডিলিট, এবং ম্যানুয়াল সার্ভার Ping।");
        subtitle.setTextSize(15);
        subtitle.setTextColor(0xFF555555);
        subtitle.setPadding(0, dp(6), 0, dp(12));
        root.addView(subtitle, new LinearLayout.LayoutParams(-1, -2));

        permissionButton = new Button(this);
        permissionButton.setText("ফাইল অ্যাক্সেস অনুমতি দিন");
        permissionButton.setOnClickListener(v -> requestAllFilesAccess());
        root.addView(permissionButton, new LinearLayout.LayoutParams(-1, -2));

        connectButton = new Button(this);
        connectButton.setText("সার্ভার Connect / Ping");
        connectButton.setOnClickListener(v -> connectOrPing());
        root.addView(connectButton, new LinearLayout.LayoutParams(-1, -2));

        requestsButton = new Button(this);
        requestsButton.setText("সার্ভারের অনুরোধ দেখুন");
        requestsButton.setOnClickListener(v -> checkServerRequests());
        root.addView(requestsButton, new LinearLayout.LayoutParams(-1, -2));

        pathView = new TextView(this);
        pathView.setTextSize(13);
        pathView.setTextColor(0xFF444444);
        pathView.setPadding(0, dp(10), 0, dp(8));
        root.addView(pathView, new LinearLayout.LayoutParams(-1, -2));

        statusView = new TextView(this);
        statusView.setTextSize(15);
        statusView.setTextColor(0xFF666666);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(0, dp(8), 0, dp(8));
        statusView.setText("প্রথমবার সার্ভার Connect করতে বাটন চাপুন।");
        root.addView(statusView, new LinearLayout.LayoutParams(-1, -2));

        listView = new ListView(this);
        adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        listView.setAdapter(adapter);
        root.addView(listView, new LinearLayout.LayoutParams(-1, 0, 1));

        listView.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= shownFiles.size()) return;
            File selected = shownFiles.get(position);
            if (selected.isDirectory()) openDirectory(selected);
            else Toast.makeText(this, "দীর্ঘক্ষণ চাপলে ফাইল ডিলিটের অপশন আসবে", Toast.LENGTH_SHORT).show();
        });
        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= shownFiles.size()) return true;
            File selected = shownFiles.get(position);
            if (selected.isDirectory()) {
                Toast.makeText(this, "নিরাপত্তার জন্য Folder ডিলিট অপশন নেই।", Toast.LENGTH_SHORT).show();
            } else {
                confirmDeleteFile(selected);
            }
            return true;
        });
        setContentView(root);
    }

    private void confirmDeleteFile(File file) {
        new AlertDialog.Builder(this)
                .setTitle("ফাইল ডিলিট করবেন?")
                .setMessage("এই ফাইলটি ফোন থেকে স্থায়ীভাবে মুছে যাবে:\n\n" + file.getAbsolutePath() + "\n\nএই কাজ ফিরিয়ে আনা নাও যেতে পারে।")
                .setNegativeButton("বাতিল", (d, w) -> { })
                .setPositiveButton("ডিলিট", (d, w) -> {
                    if (!hasAllFilesAccess()) {
                        Toast.makeText(this, "আগে ফাইল অ্যাক্সেস অনুমতি দিন।", Toast.LENGTH_LONG).show();
                        return;
                    }
                    boolean deleted = file.isFile() && file.delete();
                    if (deleted) {
                        Toast.makeText(this, "ফাইল ডিলিট হয়েছে", Toast.LENGTH_SHORT).show();
                        openDirectory(currentDir);
                    } else {
                        Toast.makeText(this, "ফাইল ডিলিট করা যায়নি। অনুমতি বা ফাইলের অবস্থা পরীক্ষা করুন।", Toast.LENGTH_LONG).show();
                    }
                }).show();
    }

    private void requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Exception e) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                startActivity(intent);
            }
        } else {
            openDirectory(Environment.getExternalStorageDirectory());
        }
    }

    private void showPermissionNeeded() {
        pathView.setText("স্টোরেজ: অনুমতি প্রয়োজন");
        statusView.setText("Android-এর All files access সেটিংসে গিয়ে অনুমতি চালু করুন।");
        permissionButton.setVisibility(View.VISIBLE);
        adapter.clear();
        shownFiles.clear();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (root != null) {
            if (hasAllFilesAccess()) {
                permissionButton.setVisibility(View.GONE);
                if (currentDir == null || !currentDir.exists()) openDirectory(Environment.getExternalStorageDirectory());
                else openDirectory(currentDir);
            } else showPermissionNeeded();
        }
    }

    private void openDirectory(File directory) {
        if (!hasAllFilesAccess()) { showPermissionNeeded(); return; }
        if (directory == null || !directory.isDirectory() || !directory.canRead()) {
            statusView.setText("এই ফোল্ডারটি পড়া যাচ্ছে না।");
            return;
        }
        currentDir = directory;
        pathView.setText("বর্তমান ফোল্ডার: " + directory.getAbsolutePath());
        permissionButton.setVisibility(View.GONE);
        shownFiles.clear();
        File[] files = directory.listFiles();
        if (files == null) {
            adapter.clear();
            statusView.setText("ফাইল তালিকা পাওয়া যায়নি।");
            return;
        }
        Arrays.sort(files, (a, b) -> {
            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });
        List<String> labels = new ArrayList<>();
        if (directory.getParentFile() != null) {
            shownFiles.add(directory.getParentFile());
            labels.add("⬆ .. (পেছনে)");
        }
        for (File file : files) {
            shownFiles.add(file);
            String label = file.isDirectory() ? "📁  " : "📄  ";
            label += file.getName();
            if (file.isDirectory()) label += "/";
            else label += "   (" + readableSize(file.length()) + ")";
            labels.add(label);
        }
        adapter.clear();
        adapter.addAll(labels);
        statusView.setText(files.length + " টি আইটেম");
    }

    private String readableSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024L * 1024) return new DecimalFormat("#.##").format(size / 1024.0) + " KB";
        if (size < 1024L * 1024 * 1024) return new DecimalFormat("#.##").format(size / (1024.0 * 1024)) + " MB";
        return new DecimalFormat("#.##").format(size / (1024.0 * 1024 * 1024)) + " GB";
    }

    @Override
    public void onBackPressed() {
        if (currentDir != null && currentDir.getParentFile() != null && hasAllFilesAccess()) openDirectory(currentDir.getParentFile());
        else super.onBackPressed();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
