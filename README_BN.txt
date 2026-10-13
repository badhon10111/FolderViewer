Folder Viewer — Supabase Connect/Ping + local file delete

এই ZIP-এ Android source code আছে; APK নেই। GitHub Actions দিয়ে APK build করতে হবে।

যা যোগ হয়েছে:
1. "সার্ভার Connect / Ping" বাটন।
2. প্রথমবার চাপলে DEVICE_ENROLLMENT_CODE চাইবে। এই মানটি Supabase Edge Function Secrets-এ DEVICE_ENROLLMENT_CODE-এর Value-এর সঙ্গে হুবহু মিলতে হবে।
3. অ্যাপ নিজে একটি random device token তৈরি করে private app storage-এ রাখবে; token বা enrollment code source code-এ রাখা নেই।
4. প্রথমবার register সফল হলে device ID ও token সংরক্ষণ হবে। পরেরবার একই বাটন চাপলে ping হবে।
5. ফাইলের ওপর দীর্ঘক্ষণ চাপলে শুধু সাধারণ ফাইলের জন্য Delete confirmation দেখাবে। নিশ্চিত না করলে ফাইল মুছবে না। Folder recursive delete করা হয় না।

নিরাপত্তা:
- sb_secret/service_role key কখনো অ্যাপে বা GitHub-এ দেবেন না। Publishable key client-এ ব্যবহারের জন্য।
- enrollment code শেয়ার করবেন না। আগে স্ক্রিনশটে দেখা গিয়ে থাকলে Supabase Secrets-এ নতুন value সেট করুন।
- Delete এখন কেবল ফোনে ব্যবহারকারীর স্পষ্ট confirmation-এর পর স্থানীয় ফাইল মুছে। Server-side remote delete request/panel এই সংস্করণে এখনো চালু হয়নি; server থেকে অনুরোধ পেলেই স্বয়ংক্রিয়ভাবে মুছে ফেলার ব্যবস্থা ইচ্ছাকৃতভাবে নেই।
- প্রথমবার Connect করতে ইন্টারনেট এবং All files access অনুমতি প্রয়োজন হতে পারে।

GitHub:
1. ZIP extract করুন।
2. ভেতরের FolderViewer_AllFilesAccess ফোল্ডারের ফাইলগুলো repository root-এ একই path অনুযায়ী replace করুন।
3. Commit changes করুন।
4. Actions > Build Android APK workflow সফল হলে FolderViewer-debug-apk artifact ডাউনলোড করুন।
