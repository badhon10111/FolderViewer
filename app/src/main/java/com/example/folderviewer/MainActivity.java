package com.example.folderviewer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.xmlpull.v1.XmlPullParser;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;

public class MainActivity extends Activity {
    private static final int PICK_FOLDER = 1001;
    private LinearLayout root, list;
    private TextView pathView, emptyView;
    private Uri treeUri;
    private String currentDocumentId;
    private final Deque<String> history = new ArrayDeque<>();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        showWelcome();
    }

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(18, 18, 18, 12);
        root.setBackgroundColor(0xFFF7F7F7);

        TextView title = new TextView(this);
        title.setText("Folder Viewer");
        title.setTextSize(24);
        title.setTextColor(0xFF111111);
        root.addView(title);

        TextView note = new TextView(this);
        note.setText("শুধু ফাইল ও ফোল্ডার দেখা যাবে। কোনো ফাইল পরিবর্তন বা ডিলিট করা হবে না।");
        note.setTextSize(14);
        note.setTextColor(0xFF555555);
        note.setPadding(0, 8, 0, 12);
        root.addView(note);

        LinearLayout actions = new LinearLayout(this);
        Button choose = new Button(this);
        choose.setText("ফোল্ডার নির্বাচন");
        choose.setOnClickListener(v -> pickFolder());
        actions.addView(choose, new LinearLayout.LayoutParams(0, -2, 1));

        Button back = new Button(this);
        back.setText("পেছনে");
        back.setOnClickListener(v -> goBack());
        actions.addView(back, new LinearLayout.LayoutParams(-2, -2));
        root.addView(actions);

        pathView = new TextView(this);
        pathView.setTextColor(0xFF333333);
        pathView.setTextSize(13);
        pathView.setPadding(0, 12, 0, 8);
        root.addView(pathView);

        ScrollView scroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        emptyView = new TextView(this);
        emptyView.setTextColor(0xFF777777);
        emptyView.setTextSize(15);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setText("প্রথমে ফোল্ডার নির্বাচন করুন।");
        list.addView(emptyView);

        setContentView(root);
    }

    private void showWelcome() {
        pathView.setText("কোনো ফোল্ডার নির্বাচিত নয়");
    }

    private void pickFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, PICK_FOLDER);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_FOLDER && resultCode == RESULT_OK && data != null) {
            treeUri = data.getData();
            if (treeUri == null) return;
            int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try { getContentResolver().takePersistableUriPermission(treeUri, flags); }
            catch (Exception ignored) {}
            currentDocumentId = DocumentsContract.getTreeDocumentId(treeUri);
            history.clear();
            showDirectory(currentDocumentId);
        }
    }

    private void showDirectory(String documentId) {
        if (treeUri == null) return;
        currentDocumentId = documentId;
        list.removeAllViews();
        pathView.setText("Folder ID: " + documentId);
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId);
        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
        };
        try (android.database.Cursor cursor = getContentResolver().query(childrenUri, projection, null, null,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME + " ASC")) {
            if (cursor == null) throw new IllegalStateException("ফাইল তালিকা পড়া যায়নি");
            if (!cursor.moveToFirst()) {
                addMessage("এই ফোল্ডার খালি অথবা পড়ার অনুমতি নেই।");
                return;
            }
            do {
                String id = cursor.getString(0);
                String name = cursor.getString(1);
                String mime = cursor.getString(2);
                long size = cursor.isNull(3) ? -1 : cursor.getLong(3);
                long modified = cursor.isNull(4) ? 0 : cursor.getLong(4);
                boolean isDir = DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
                addEntry(id, name == null ? "(নাম নেই)" : name, isDir, size, modified);
            } while (cursor.moveToNext());
        } catch (Exception e) {
            addMessage("এই ফোল্ডার পড়া যায়নি: " + e.getClass().getSimpleName());
            Toast.makeText(this, "অনুমতি বা ফোল্ডার অ্যাক্সেস পরীক্ষা করুন", Toast.LENGTH_LONG).show();
        }
    }

    private void addEntry(String id, String name, boolean isDir, long size, long modified) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(12, 12, 12, 12);
        row.setBackgroundColor(0xFFFFFFFF);
        TextView label = new TextView(this);
        label.setText((isDir ? "📁  " : "📄  ") + name);
        label.setTextSize(16);
        label.setTextColor(0xFF202020);
        row.addView(label);
        TextView details = new TextView(this);
        details.setText(isDir ? "ফোল্ডার" : (size >= 0 ? formatSize(size) : "ফাইলের আকার জানা নেই"));
        details.setTextSize(12);
        details.setTextColor(0xFF666666);
        row.addView(details);
        row.setOnClickListener(v -> {
            if (isDir) {
                history.push(currentDocumentId);
                showDirectory(id);
            } else {
                new AlertDialog.Builder(this)
                        .setTitle(name)
                        .setMessage("এটি শুধু দেখার অ্যাপ। ফাইল খোলা, সম্পাদনা বা ডিলিট করা হয় না।\n\n" + (size >= 0 ? "আকার: " + formatSize(size) : "আকার অজানা"))
                        .setPositiveButton("ঠিক আছে", null)
                        .show();
            }
        });
        list.addView(row);
        View divider = new View(this);
        divider.setBackgroundColor(0xFFE0E0E0);
        list.addView(divider, new LinearLayout.LayoutParams(-1, 1));
    }

    private void addMessage(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xFF666666);
        tv.setPadding(8, 20, 8, 20);
        list.addView(tv);
    }

    private void goBack() {
        if (!history.isEmpty()) showDirectory(history.pop());
        else if (treeUri != null) showDirectory(DocumentsContract.getTreeDocumentId(treeUri));
        else Toast.makeText(this, "আগে একটি ফোল্ডার নির্বাচন করুন", Toast.LENGTH_SHORT).show();
    }

    private String formatSize(long n) {
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return String.format(java.util.Locale.US, "%.1f KB", n / 1024.0);
        if (n < 1024L * 1024L * 1024L) return String.format(java.util.Locale.US, "%.1f MB", n / (1024.0 * 1024));
        return String.format(java.util.Locale.US, "%.1f GB", n / (1024.0 * 1024 * 1024));
    }
  }
