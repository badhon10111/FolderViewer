Folder Viewer - All Files Access build

এই সংস্করণে Android-এর "All files access" সেটিংস খোলার ব্যবস্থা আছে।
এটি ZArchiver-এর মতো বিশেষ অনুমতি চায়, তবে অ্যাপের নিজস্ব UI-তে ফাইল মুছা বা পরিবর্তনের সুবিধা নেই।

GitHub-এ ব্যবহার:
1. ZIP extract করুন।
2. ভেতরের ফাইলগুলো আপনার FolderViewer repository-র root-এ একই path অনুযায়ী upload/replace করুন।
3. .github/workflows/build-apk.yml-ও replace করুন।
4. Commit changes করুন।
5. Actions-এ FolderViewer-debug-apk artifact ডাউনলোড করুন।

গুরুত্বপূর্ণ:
- All files access খুব বিস্তৃত অনুমতি। শুধু নিজের পরীক্ষার ফোনে ব্যবহার করুন।
- Android সেটিংসে অনুমতি চালু করার পর অ্যাপে ফিরে এলে ফাইল তালিকা দেখাবে।
- এই ZIP-এ APK নেই; GitHub Actions দিয়ে build করার source code আছে।
