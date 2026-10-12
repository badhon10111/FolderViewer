Folder Viewer — Android source project

এই অ্যাপটি শুধু ব্যবহারকারীর বেছে দেওয়া ফোল্ডার ও তার ভেতরের ফাইল/সাবফোল্ডার দেখায়।
এতে Delete, Edit, Upload, Root access বা Server নেই।

APK তৈরি:
1. Android Studio-তে এই FolderViewer ফোল্ডারটি Open করুন।
2. Gradle sync সম্পন্ন হতে দিন (Android SDK এবং ইন্টারনেট লাগতে পারে)।
3. Build > Build Bundle(s) / APK(s) > Build APK(s) নির্বাচন করুন।
4. APK সাধারণত app/build/outputs/apk/debug/app-debug.apk-এ তৈরি হবে।
5. নিজের ফোনে ইনস্টল করার আগে APK-টি পরীক্ষা করুন।

সীমাবদ্ধতা:
- Android-এর Storage Access Framework-এ ব্যবহারকারী যে ফোল্ডার অনুমোদন করেন শুধু সেটিই দেখা যায়।
- /data/data এবং সুরক্ষিত সিস্টেম ফাইল দেখা যায় না।
- এই পরিবেশে Android SDK/Gradle নেই, তাই এখানে APK build বা runtime test করা হয়নি।


Cloud Build (GitHub Actions):
1. ZIP extract করুন।
2. FolderViewer-এর ভেতরের সব ফাইল নতুন GitHub repository-তে আপলোড করুন (root-এ settings.gradle থাকবে)।
3. Actions ট্যাবে "Build Android APK" workflow খুলে Run workflow চাপুন।
4. Build সফল হলে Artifacts থেকে FolderViewer-debug-apk ডাউনলোড করুন।
5. ZIP-এর ভেতরের app-debug.apk VPhoneOS/Android-এ ইনস্টল করে পরীক্ষা করুন।

এ জন্য GitHub account ও internet দরকার। এই workflow-তে APK এখানে বিল্ড বা পরীক্ষা করা হয়নি; GitHub Actions-এ build সফল হলে তবেই APK পাওয়া যাবে।
