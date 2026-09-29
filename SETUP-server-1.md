# Account server setup (Cloudflare, free)

Ye server do kaam karta hai:
1. **Account seat** — "ek account = ek active phone + doosre phone ko approve karna"
2. **Cloud Backup** — Google Drive ki jagah, encrypted backups seedha is server ki apni
   storage (Cloudflare R2) mein jaate hain

Dono mein sirf email, ek chhota hash, aur (backup ke liye) tumhari **already-encrypted**
backup file save hoti hai. Server khud kabhi bhi photos nahi dekh sakta — file already
tumhare vault password se encrypt hoke aati hai.

## 1. Worker banao
1. dash.cloudflare.com par free account banao / login karo.
2. **Workers & Pages → Create → Create Worker**. Naam: `vvault-accounts` → **Deploy**.
3. **Edit code** dabao, poora purana code hata kar `worker.js` ka poora code paste karo → **Deploy**.

## 2. Database jodo (account seat ke liye)
1. **Storage & databases → D1 SQL database → Create**. Naam: `vvault` → Create.
2. Wapas Worker par jao: **Settings → Bindings → Add → D1 database**.
3. **Variable name** mein bilkul `DB` likho, database `vvault` chuno → Save/Deploy.
   (Tables apne aap ban jayenge, kuch SQL nahi chalani.)

## 3. R2 bucket jodo (Cloud Backup ke liye)
1. **Storage & databases → R2 → Create bucket**. Naam: `vvault-backups` → Create.
   (Pehli baar R2 use karne par Cloudflare card details maang sakta hai — free tier
   10GB tak koi charge nahi lagta.)
2. Wapas Worker par jao: **Settings → Bindings → Add → R2 bucket**.
3. **Variable name** mein bilkul `BUCKET` likho, bucket `vvault-backups` chuno → Save/Deploy.

## 4. Check karo
Worker ka URL (jaise `https://vvault-accounts.NAAM.workers.dev`) browser mein kholo.
Ye dikhna chahiye: `{"ok":true,"service":"vvault-accounts"}`

## 5. App mein URL daalo
`vault.html` mein ye line dhundo aur apna URL likho (aakhir mein `/` nahi):

    const ACCOUNT_SERVER_URL = 'https://vvault-accounts.NAAM.workers.dev';

Ye ek hi line dono features (account seat + cloud backup) on kar deti hai — khali (`''`)
chhodoge to dono band rahenge. Phir `vault.html` GitHub par upload karke naya APK banao.

## Kaise kaam karta hai (Account seat)
- Koi phone active nahi (naya, sign-out kiya, ya 14 din se chup) -> seedha login, popup nahi.
- Ek phone active hai -> naya phone "Waiting for approval" dikhata hai, active phone par
  Approve / Deny popup aata hai (vault unlock hone par, lock screen par nahi).
- Approve -> account naye phone par chala jata hai, purane phone par cloud backup band ho jata hai
  (uska vault aur photos wahin rehte hain).

## Kaise kaam karta hai (Cloud Backup)
- User "Sign in with Google" karta hai (sirf email verify karne ke liye, koi Drive
  permission nahi maangi jaati).
- Backup banate waqt: vault ka data pehle tumhare vault password se **device par hi**
  encrypt hota hai, phir wahi encrypted file `/v1/backup/upload` route se R2 mein jaati hai,
  us user ki email se juda ek folder-jaisi key ke andar (`backups/<email>/<timestamp>.vault`).
- Restore karte waqt: file wapas mangwakar, tumhare vault password se **device par hi**
  decrypt hoti hai. Server kabhi plaintext nahi dekhta.
- Har request Google ke access token se verify hoti hai ki wo email wahi hai jiska
  claim kiya ja raha hai — koi user kisi doosre ki backup na dekh sakta hai na delete kar sakta hai.
- Purane backups (latest 5 se zyada) khud-ba-khud delete ho jate hain.

## Dhyan rakhne wali baatein
- Popup tabhi dikhta hai jab active phone par app khula ho (ya request ke 30 min ke andar khule).
  App band hone par bhi notification chahiye to alag se Firebase push lagana padega.
- Sirf "Continue with Google" wale login par account-seat lagu hota hai. Email-OTP wale
  login par nahi (usme cloud backup bhi available nahi hoga, kyunki wo Google se juda nahi).
- Server band ho ya net na ho to login/backup rukta nahi (vault phone par hi hai; backup
  agli baar try hoga).
- Abhi har backup file ~25MB tak ho sakti hai. Isse bade vault ke liye upload fail hoga
  ("Your vault is too large for a cloud backup right now").
- Free plan ki limit (D1: roz 1 lakh writes, R2: 10GB storage + roz kaafi requests,
  Workers: roz 1 lakh requests) chhote user base ke liye kaafi hai. Zyada users hone par
  R2 ka paid tier lagana pad sakta hai (~$0.015/GB/month — bahut sasta).
