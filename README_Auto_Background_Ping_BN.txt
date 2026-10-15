Folder Viewer — Automatic Background Ping (বাংলা)

এই আপডেটে:
- MainActivity-এর “সার্ভার Connect / Ping” বোতাম সরানো হয়েছে।
- আগে থেকে নিবন্ধিত ডিভাইস হলে অ্যাপ খোলার সঙ্গে foreground service শুরু হয়।
- নতুন ইনস্টলে একবার enrollment code দিয়ে নিবন্ধন করতে হবে; সফল হলে service নিজে চালু হয়।
- ইন্টারনেট যাচাই করা গেলে সঙ্গে সঙ্গে ping, এরপর প্রতি ২ মিনিটে ping।
- Wi-Fi/মোবাইল নেটওয়ার্ক আছে কিন্তু ইন্টারনেট নেই বা ping ব্যর্থ হলে সর্বোচ্চ ৫টি চেষ্টা, প্রতিটির মাঝে ১০ সেকেন্ড। তারপর অপেক্ষা করে; নেটওয়ার্ক বদলালে/ইন্টারনেট ফিরলে আবার চেষ্টা করে।
- চলমান সার্ভিসের জন্য স্থায়ী notification দেখায়।
- অন্য ফিচার (ফাইল ব্রাউজিং, সার্ভার requests, location workflow) বাদ দেওয়ার উদ্দেশ্য নেই।

ইনস্টল/পরীক্ষা:
1. ZIP Extract করে GitHub-এ সোর্স আপডেট করো।
2. GitHub Actions থেকে নতুন APK build করো।
3. APK ইনস্টল করে অ্যাপ একবার খোলা রাখো এবং notification permission চাইলে অনুমতি দাও।
4. Supabase panel-এ device online দেখো। Wi-Fi বন্ধ/চালু করে এবং ইন্টারনেট বিচ্ছিন্ন অবস্থায় পরীক্ষা করো।

সীমাবদ্ধতা ও গুরুত্বপূর্ণ:
- Android-এর Doze, ব্যাটারি অপ্টিমাইজেশন, OEM battery manager, Force stop, ফোন reboot এবং Android সংস্করণ service-কে থামাতে পারে। Battery optimization-এ Folder Viewer-কে unrestricted/Don't optimize করা লাগতে পারে। Force stop-এর পর ব্যবহারকারী অ্যাপ পুনরায় না খোলা পর্যন্ত Android service চালু নাও করতে পারে।
- specialUse foreground service Android 14+ এ platform/Play policy অনুযায়ী ঘোষণা ও review প্রয়োজন হতে পারে। এটি সরাসরি APK দিয়ে ইনস্টল করলেও ফোনের OS আচরণ আলাদা হতে পারে।
- এই পরিবেশে Gradle build বা বাস্তব ফোন পরীক্ষা করা হয়নি। ZIP-এর ফাইল পরিবর্তন ও কাঠামো পরীক্ষা করা হয়েছে, কিন্তু APK compile/runtime সফলতা নিশ্চিত নয়। Build ব্যর্থ হলে GitHub Actions log পাঠাও।
- পিংয়ের জন্য শুধু Supabase publishable key ব্যবহার করা হয়েছে; Secret key APK-তে যোগ করা হয়নি।
