# Account server setup (Cloudflare, free)

Ye server "ek account = ek active phone + doosre phone ko approve karna" wala feature chalata hai.
Isme photos ya passwords kabhi nahi aate: sirf email, active phone ka ID, ek hash aur time.

## 1. Worker banao
1. dash.cloudflare.com par free account banao / login karo.
2. **Workers & Pages → Create → Create Worker**. Naam: `vvault-accounts` → **Deploy**.
3. **Edit code** dabao, poora purana code hata kar `worker.js` ka poora code paste karo → **Deploy**.

## 2. Database jodo
1. **Storage & databases → D1 SQL database → Create**. Naam: `vvault` → Create.
2. Wapas Worker par jao: **Settings → Bindings → Add → D1 database**.
3. **Variable name** mein bilkul `DB` likho, database `vvault` chuno → Save/Deploy.
   (Tables apne aap ban jayenge, kuch SQL nahi chalani.)

## 3. Check karo
Worker ka URL (jaise `https://vvault-accounts.NAAM.workers.dev`) browser mein kholo.
Ye dikhna chahiye: `{"ok":true,"service":"vvault-accounts"}`

## 4. App mein URL daalo
`vault.html` mein ye line dhundo aur apna URL likho (aakhir mein `/` nahi):

    const ACCOUNT_SERVER_URL = 'https://vvault-accounts.NAAM.workers.dev';

Khali ('') chhodoge to feature band rahega. Phir `vault.html` GitHub par upload karke naya APK banao.

## Kaise kaam karta hai
- Koi phone active nahi (naya, sign-out kiya, ya 14 din se chup) -> seedha login, popup nahi.
- Ek phone active hai -> naya phone "Waiting for approval" dikhata hai, active phone par
  Approve / Deny popup aata hai (vault unlock hone par, lock screen par nahi).
- Approve -> account naye phone par chala jata hai, purane phone par Drive backup band ho jata hai
  (uska vault aur photos wahin rehte hain).

## Dhyan rakhne wali baatein
- Popup tabhi dikhta hai jab active phone par app khula ho (ya request ke 30 min ke andar khule).
  App band hone par bhi notification chahiye to alag se Firebase push lagana padega.
- Sirf "Continue with Google" wale login par ye lagu hota hai. Email-OTP wale login par nahi.
- Server band ho ya net na ho to login rukta nahi (vault phone par hi hai).
- Free plan ki limit (D1: roz 1 lakh writes, Workers: roz 1 lakh requests) chhote user base ke liye kaafi hai.
