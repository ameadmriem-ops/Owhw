/**
 * Firebase Admin SDK Script to grant Admin/Assistant Custom Claims
 * Project: hamza-4b70c (Black Anime)
 *
 * Target Admin Account:
 * - Email: ameadmriem@gmail.com
 * - UID:   RDp9BU99UJcG60hcBfMFNrxaUwt2
 *
 * Usage:
 * 1. Download Service Account JSON from Firebase Console:
 *    Firebase Console -> Project Settings -> Service accounts -> Generate new private key
 * 2. Save it as 'serviceAccountKey.json' in 'admin_scripts/' or project root
 * 3. Run:
 *    node set_custom_claims.js
 *
 * Optional CLI arguments:
 *    node set_custom_claims.js --verify
 *    node set_custom_claims.js --list
 *    node set_custom_claims.js --role=assistant --email=assistant@example.com
 */

const fs = require('fs');
const path = require('path');
const admin = require('firebase-admin');

const TARGET_ADMIN_EMAIL = 'ameadmriem@gmail.com';
const TARGET_ADMIN_UID = 'RDp9BU99UJcG60hcBfMFNrxaUwt2';

// 1. Locate Service Account Key
function findServiceAccount() {
  const possiblePaths = [
    path.join(__dirname, 'serviceAccountKey.json'),
    path.join(__dirname, '..', 'serviceAccountKey.json'),
    process.env.GOOGLE_APPLICATION_CREDENTIALS
  ].filter(Boolean);

  for (const p of possiblePaths) {
    if (fs.existsSync(p)) {
      try {
        const content = fs.readFileSync(p, 'utf8');
        return JSON.parse(content);
      } catch (err) {
        console.error(`❌ Found service account file at ${p} but failed to parse:`, err.message);
      }
    }
  }

  if (process.env.FIREBASE_SERVICE_ACCOUNT) {
    try {
      const raw = process.env.FIREBASE_SERVICE_ACCOUNT;
      const jsonStr = raw.startsWith('{') ? raw : Buffer.from(raw, 'base64').toString('utf8');
      return JSON.parse(jsonStr);
    } catch (e) {
      console.error('❌ Failed to parse FIREBASE_SERVICE_ACCOUNT env variable:', e.message);
    }
  }

  return null;
}

const serviceAccount = findServiceAccount();

if (!serviceAccount) {
  console.log(`
================================================================================
⚠️ تنبيه: ملف مفتاح الخدمة (serviceAccountKey.json) غير موجود حالياً!
================================================================================
لتشغيل السكربت ومنح صلاحية المشرف (Admin) رسمياً في Firebase:

1. افتح لوحة تحكم Firebase (Firebase Console) لمشروعك (hamza-4b70c).
2. انتقل إلى:
   ⚙️ إعدادات المشروع (Project settings) -> حسابات الخدمة (Service accounts)
3. اضغط على الزر:
   "إنشاء مفتاح خاص جديد" (Generate new private key)
4. احفظ الملف المُنَزَّل باسم:
   serviceAccountKey.json
5. ضعه داخل هذا المجلد (admin_scripts/) ثم شغّل:
   node set_custom_claims.js
================================================================================
`);
  process.exit(0);
}

// 2. Initialize Firebase Admin
try {
  admin.initializeApp({
    credential: admin.credential.cert(serviceAccount)
  });
  console.log('✅ Initialized Firebase Admin SDK with project:', serviceAccount.project_id || 'hamza-4b70c');
} catch (e) {
  console.error('❌ Failed to initialize Firebase Admin SDK:', e);
  process.exit(1);
}

const auth = admin.auth();
const db = admin.firestore();

// 3. Command Line Argument Parsing
const args = process.argv.slice(2);
const isVerifyOnly = args.includes('--verify');
const isListOnly = args.includes('--list');
const roleArg = args.find(a => a.startsWith('--role='));
const emailArg = args.find(a => a.startsWith('--email='));
const uidArg = args.find(a => a.startsWith('--uid='));

async function findUser(uid, email) {
  let user = null;
  if (uid) {
    try {
      user = await auth.getUser(uid);
    } catch (e) {
      if (e.code !== 'auth/user-not-found') throw e;
    }
  }
  if (!user && email) {
    try {
      user = await auth.getUserByEmail(email);
    } catch (e) {
      if (e.code !== 'auth/user-not-found') throw e;
    }
  }
  return user;
}

async function run() {
  try {
    if (isListOnly) {
      console.log('📋 Fetching users from Firebase Auth...');
      const list = await auth.listUsers(50);
      for (const u of list.users) {
        console.log(`- UID: ${u.uid} | Email: ${u.email} | Claims: ${JSON.stringify(u.customClaims || {})}`);
      }
      return;
    }

    // Determine target
    let targetUid = uidArg ? uidArg.split('=')[1].trim() : TARGET_ADMIN_UID;
    let targetEmail = emailArg ? emailArg.split('=')[1].trim() : TARGET_ADMIN_EMAIL;
    let targetRole = roleArg ? roleArg.split('=')[1].trim().toUpperCase() : 'ADMIN';

    console.log(`🔍 Searching for account (UID: ${targetUid}, Email: ${targetEmail})...`);
    let user = await findUser(targetUid, targetEmail);

    if (!user) {
      console.error(`❌ User not found with UID '${targetUid}' or Email '${targetEmail}'.`);
      console.error(`يرجى التأكد من تسجيل الحساب في التطبيق أو في Firebase Console أولاً.`);
      return;
    }

    console.log(`👤 Found user: ${user.email} (UID: ${user.uid}, emailVerified: ${user.emailVerified})`);

    if (isVerifyOnly) {
      console.log('Current custom claims:', user.customClaims);
      return;
    }

    // If granting ADMIN, verify identity matches designated owner
    if (targetRole === 'ADMIN') {
      const emailMatches = (user.email || '').toLowerCase() === TARGET_ADMIN_EMAIL.toLowerCase();
      const uidMatches = user.uid === TARGET_ADMIN_UID;
      if (!emailMatches && !uidMatches) {
        throw new Error(`Refusing to grant ADMIN: Target must be ${TARGET_ADMIN_EMAIL} or UID ${TARGET_ADMIN_UID}`);
      }

      // Automatically verify email if needed so it never blocks the admin
      if (!user.emailVerified) {
        await auth.updateUser(user.uid, { emailVerified: true });
        console.log('✅ Marked user email as verified (emailVerified = true).');
      }

      // Set Custom Claims for Admin
      await auth.setCustomUserClaims(user.uid, {
        admin: true,
        role: 'ADMIN'
      });
      console.log(`✅ Granted Custom Claims { admin: true, role: 'ADMIN' } to ${user.email}`);

      // Sync Firestore profile
      try {
        await db.collection('users').doc(user.uid).set({
          uid: user.uid,
          email: user.email,
          role: 'ADMIN',
          isAdmin: true,
          updatedAt: admin.firestore.FieldValue.serverTimestamp()
        }, { merge: true });
        console.log(`✅ Updated Firestore document users/${user.uid} with role: 'ADMIN'`);
      } catch (fsErr) {
        console.warn('⚠️ Could not update Firestore users document directly (check Firestore enabled):', fsErr.message);
      }

    } else if (targetRole === 'ASSISTANT') {
      // Set Custom Claims for Assistant
      await auth.setCustomUserClaims(user.uid, {
        assistant: true,
        role: 'ASSISTANT'
      });
      console.log(`✅ Granted Custom Claims { assistant: true, role: 'ASSISTANT' } to ${user.email}`);

      try {
        await db.collection('users').doc(user.uid).set({
          uid: user.uid,
          email: user.email,
          role: 'ASSISTANT',
          updatedAt: admin.firestore.FieldValue.serverTimestamp()
        }, { merge: true });
        console.log(`✅ Updated Firestore document users/${user.uid} with role: 'ASSISTANT'`);
      } catch (fsErr) {
        console.warn('⚠️ Could not update Firestore users document directly:', fsErr.message);
      }
    } else {
      // Remove privileged claims (set as Viewer/User)
      await auth.setCustomUserClaims(user.uid, {
        role: 'USER'
      });
      console.log(`✅ Reset Custom Claims to USER for ${user.email}`);
    }

    // Verify claims from server
    const verified = await auth.getUser(user.uid);
    console.log(`\n🎉 Success! Verified user state on Firebase Auth:`);
    console.log(`- Email: ${verified.email}`);
    console.log(`- UID: ${verified.uid}`);
    console.log(`- Email Verified: ${verified.emailVerified}`);
    console.log(`- Custom Claims:`, verified.customClaims);
    console.log(`\nعند تسجيل الدخول في التطبيق أو تحديث الرمز (Token Refresh)، سيقرأ التطبيق الصلاحية فوراً.`);

  } catch (err) {
    console.error('❌ Operation failed:', err);
  }
}

run();
