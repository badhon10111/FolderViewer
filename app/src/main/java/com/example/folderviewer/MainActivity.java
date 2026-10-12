package com.example.folderviewer;

import android.app.Activity;
import android.os.Bundle;
import android.os.Build;
import android.os.Environment;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.InputStream;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();

        if (hasAllFilesAccess()) {
            openDirectory(Environment.getExternalStorageDirectory());
        } else {
            showPermissionNeeded();
        }
    }

    // Publishable key is intended for client use; never put a Supabase secret/service_role key here.
    private static final String SUPABASE_URL = "https://rvtqkldsapdtiocjygvc.supabase.co";
    private static final String SUPABASE_PUBLISHABLE_KEY = "sb_publishable_OA3eukLqTY8BrL3lf5trwQ_apXWqXh-";

    private void testSupabaseConnection() {
        connectButton.setEnabled(false);
        statusView.setText("Supabase সংযোগ পরীক্ষা হচ্ছে...");
        new Thread(() -> {
            HttpURLConnection connection = null;
            String message;
            try {
                URL url = new URL(SUPABASE_URL + "/rest/v1/devices?select=id&limit=1");
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);
                connection.setRequestProperty("apikey", SUPABASE_PUBLISHABLE_KEY);
                connection.setRequestProperty("Authorization", "Bearer " + SUPABASE_PUBLISHABLE_KEY);
                connection.setRequestProperty("Accept", "application/json");
                int code = connection.getResponseCode();
                if (code >= 200 && code < 300) {
                    message = "Supabase সংযোগ সফল (HTTP " + code + ")। এখনো কোনো ফাইল রিকোয়েস্ট বা ডিলিট চালু নেই।";
                } else {
                    message = "Supabase উত্তর দিয়েছে, কিন্তু সংযোগ পরীক্ষা ব্যর্থ (HTTP " + code + ")। RLS/API সেটিংস পরীক্ষা করতে হবে।";
                }
            } catch (Exception e) {
                message = "সংযোগ করা যায়নি। ইন্টারনেট ও Project URL পরীক্ষা করুন।";
            } finally {
                if (connection != null) connection.disconnect();
            }
            final String result = message;
            runOnUiThread(() -> {
                statusView.setText(result);
                connectButton.setEnabled(true);
            });
        }).start();
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
        subtitle.setText("ফাইল ও ফোল্ডার দেখা যাবে। অ্যাপ থেকে ফাইল পরিবর্তন বা ডিলিট করা যাবে না।");
        subtitle.setTextSize(15);
        subtitle.setTextColor(0xFF555555);
        subtitle.setPadding(0, dp(6), 0, dp(12));
        root.addView(subtitle, new LinearLayout.LayoutParams(-1, -2));

        permissionButton = new Button(this);
        permissionButton.setText("ফাইল অ্যাক্সেস অনুমতি দিন");
        permissionButton.setOnClickListener(v -> requestAllFilesAccess());
        root.addView(permissionButton, new LinearLayout.LayoutParams(-1, -2));

        connectButton = new Button(this);
        connectButton.setText("সার্ভার কানেকশন পরীক্ষা");
        connectButton.setOnClickListener(v -> testSupabaseConnection());
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
        root.addView(statusView, new LinearLayout.LayoutParams(-1, -2));

        listView = new ListView(this);
        adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        listView.setAdapter(adapter);
        root.addView(listView, new LinearLayout.LayoutParams(-1, 0, 1));

        listView.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= shownFiles.size()) return;
            File selected = shownFiles.get(position);
            if (selected.isDirectory()) {
                openDirectory(selected);
            } else {
                Toast.makeText(this, "শুধু দেখার জন্য: " + selected.getName(), Toast.LENGTH_SHORT).show();
            }
        });

        setContentView(root);
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
        statusView.setText("ZArchiver-এর মতো Android-এর All files access সেটিংসে গিয়ে অনুমতি চালু করুন।");
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
                if (currentDir == null || !currentDir.exists()) {
                    openDirectory(Environment.getExternalStorageDirectory());
                } else {
                    openDirectory(currentDir);
                }
            } else {
                showPermissionNeeded();
            }
        }
    }

    private void openDirectory(File directory) {
        if (!hasAllFilesAccess()) {
            showPermissionNeeded();
            return;
        }
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
            if (file.isDirectory()) {
                label += "/";
            } else {
                label += "   (" + readableSize(file.length()) + ")";
            }
            labels.add(label);
        }
        adapter.clear();
        adapter.addAll(labels);
        statusView.setText(files.length + " টি আইটেম");
        listView.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= shownFiles.size()) return;
            File selected = shownFiles.get(position);
            if (selected.isDirectory()) {
                openDirectory(selected);
            } else {
                Toast.makeText(this, "শুধু দেখার জন্য: " + selected.getName(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private String readableSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024L * 1024) return new DecimalFormat("#.##").format(size / 1024.0) + " KB";
        if (size < 1024L * 1024 * 1024) return new DecimalFormat("#.##").format(size / (1024.0 * 1024)) + " MB";
        return new DecimalFormat("#.##").format(size / (1024.0 * 1024 * 1024)) + " GB";
    }

    @Override
    public void onBackPressed() {
        if (currentDir != null && currentDir.getParentFile() != null && hasAllFilesAccess()) {
            openDirectory(currentDir.getParentFile());
        } else {
            super.onBackPressed();
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
