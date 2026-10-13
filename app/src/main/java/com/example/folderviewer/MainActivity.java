package com.example.folderviewer;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Build;
import android.os.Environment;
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
