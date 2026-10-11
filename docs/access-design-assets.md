# Access UI assets

The Remanence UI keeps the existing Hold palette, DejaVu typography and press
components. Only the Google sign-in button uses provider branding.

- Google G: https://developers.google.com/static/identity/images/g-logo.png
- Button requirements: https://developers.google.com/identity/branding-guidelines
- Google Sans Medium: official Google Fonts CSS, family `Google Sans`, weight 500.
- Font license: `android/app/src/main/res/raw/google_sans_ofl.txt` (SIL OFL 1.1).

No account, provider, PRF, server, ciphertext or recovery-readiness contract is
changed by these presentation components. Recovery-code bytes still live only
in the original protected, non-saved composable state and are cleared on disposal.
Visual fixture screenshots are not real provider/recovery acceptance evidence.
