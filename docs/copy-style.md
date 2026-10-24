# Interface copy: voice and translation

Applies to the web app (`web/src/i18n/locales/*.json`). The Android strings
(`android/app/src/main/res/values*/strings.xml`) should follow the same rules.

## Voice

Remanence is something personal between two people, not a bank. The copy is
warm, plain and short.

- **Lowercase, no closing period.** Every string starts lowercase (brand and
  product names like Google and JPEG keep their case; "remanence" is always
  lowercase). A string with two sentences keeps the period between them and
  drops the last one. Questions keep `?`, progress keeps `…`.
- **Talk like a friend, not a manual.** "keep the whole postcard in the
  frame, ideally in even light", not "Position the postcard fully within the
  frame under even lighting."
- **Avoid direct address where you can.** Buttons are verbs ("send
  postcard"), hints describe ("3 to 5 photos"). Where address is needed, use
  the polite form (ru «вы», uk «ви», de «Sie»…): recipients are often older
  relatives.
- **Errors are clear first.** Say what happened and what to do next. No
  jokes in errors, nothing cute about money, accounts or losing work.
- **No developer words.** Never: keys, unlock, shell, fingerprint, session,
  directory, server reply, visit, UTF-8. The user signs in, confirms with a
  passkey, opens and sends postcards.
- **Don't sound generated.** No dramatic dashes, no "and that's it", no
  three-part slogans, no reassurance filler ("everything is fine", "nothing
  was shown"). Say it once.
- **Short labels must fit.** Tab labels (`navScan`, `navMake`, `navHistory`,
  `settingsTitle`) sit in a phone tab bar: one short word.

## Glossary

| concept | en | ru | uk |
|---|---|---|---|
| the physical card | postcard | открытка | листівка |
| its picture side | picture side | лицевая сторона | лицьовий бік |
| public name | username | имя | ім’я |
| passkey | passkey | ключ доступа | ключ доступу |
| make one | make a postcard | собрать открытку | зібрати листівку |
| the content inside | photos and words | фото и слова | фото й слова |
| layout of the photos | layout | оформление | оформлення |

Use the platform's own word for "passkey" in each language (what Google and
Apple show in system dialogs).

## Adding a language

1. Copy `web/src/i18n/locales/en.json` to `<code>.json` and translate the
   values. Keep every key; never add new ones. `context.json` says where
   each string appears and how much room it has.
2. Import it in `web/src/i18n/catalog.ts`, add the code to `languages`, and add
   its name to `web/src/ui/languageOptions.ts`.
3. Run the web tests: `test/i18n.test.ts` checks the house style and that the
   keys match English. Any string left out falls back to English.
4. Have a native speaker read it in the app, on a phone.
