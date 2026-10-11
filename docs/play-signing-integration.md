# Remanence Play signing integration — 2026-10-11

Permanent package:`dev.hryshyn.remanence`. Owner supplied the following public
Play app-signing fingerprints after uploading the first vc35 Internal draft.
They are not the upload certificate. Actual Play-delivered APK verification is
still required; do not infer device/PRF acceptance from configuration.

| Google Cloud Android OAuth client name | SHA-1 |
| --- | --- |
| Remanence Play Classical | `1F:4E:89:3B:5C:BD:05:CB:0F:26:96:9A:F0:07:6A:EA:4A:19:DE:8E` |
| Remanence Play Hybrid Classical | `D5:36:53:4B:DA:99:41:77:6F:94:A2:D0:B4:9D:29:ED:14:DE:B5:3C` |
| Remanence Play Hybrid PQC | `44:2D:5F:04:2A:81:58:98:B0:E1:D7:49:FA:D2:FC:54:46:56:AE:00` |

Owner created the three Android clients in the existing181177070441 project:

| Signing key | Android client ID |
| --- | --- |
| Classical | `181177070441-fk00qgofaajsfnae17ell5penam1frcv.apps.googleusercontent.com` |
| Hybrid classical | `181177070441-mqdb3brtg0khfvh8s7d02oag6qdn48nn.apps.googleusercontent.com` |
| Hybrid PQC | `181177070441-lmhlc38ded2mcjchcbnseovvu2g2mto2.apps.googleusercontent.com` |

Each uses the permanent package and corresponding SHA-1 per owner's mapping.
Add these exact IDs as trusted presenters, not audiences. Keep the existing
Web audience and debug Android client. No new Web client or client secret needed.
An actual Play login still needs to verify the owner's Cloud configuration.

## DAL fingerprints and native origins

Classical SHA256:
`DA:BD:AE:D8:1E:4F:2A:63:70:2A:21:A4:EC:81:58:ED:9F:44:55:CD:A7:8E:70:40:93:83:AE:15:A0:55:95:8C`

Native origin:`android:apk-key-hash:2r2u2B5PKmNwKiGk7IFY7Z9EVc2njnBAk4OuFaBVlYw`

Hybrid classical SHA256:
`A5:3E:3A:94:63:56:15:A7:C4:14:C4:B9:A0:A4:ED:E2:71:6D:5F:24:17:86:36:16:4B:F6:5D:9B:F2:D6:E2:6F`

Native origin:`android:apk-key-hash:pT46lGNWFafEFMS5oKTt4nFtXyQXhjYWS_Zdm_LW4m8`

Hybrid PQC SHA256:
`28:94:87:3B:F0:52:A2:4F:C2:47:11:8D:72:96:43:F5:45:08:B0:07:52:37:3E:71:CB:12:2A:AF:85:E7:AA:76`

Native origin:`android:apk-key-hash:KJSHO_BSok_CRxGNcpZD9UUIsAdSNz5xyxIqr4XnqnY`

Origins are unpadded base64url of the32 SHA256 digest bytes, not ASCII colon
text or APK file digest. DAL target uses exact permanent package + all three
fingerprints, both login-credentials and URL relations; preserve the existing
prfprobe/recoverytest entries and HTTPS/debug service origins.

## Remaining rollout gates

Actual Play-signed Google/PRF smoke, tested beta
consent/safety/legal deployment and deletion/moderation operations still pending.
Do not serve the beta rollout against the current schema0010 server, reset
accounts, or uninstall the owner's working recoverytest application. These are
separate installs against preserved accounts/ciphertext, not a data migration.

Official contracts:
[Play signing and three-key registration](https://support.google.com/googleplay/android-developer/answer/9842756)
and [Credential Manager DAL prerequisites](https://developer.android.com/identity/credential-manager/prerequisites).
