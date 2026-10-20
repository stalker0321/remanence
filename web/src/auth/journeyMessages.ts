export const journeyEn = {
  scanPreparing: 'Preparing the postcard on this device. Nothing is shown yet.',
  openChecking: 'Checking permission to open. Keep connected, or cancel.',
  openDenied: 'This postcard is no longer available to open. Nothing was shown.',
  openUnconfirmed: 'Opening could not be confirmed. Nothing was shown. Connect and try again; a previous request may already have been accepted.',
  openSignIn: 'Sign in to this account again before opening. Nothing was shown.',
  registrationPaused: 'New account setup is temporarily paused until the keys can be safely recovered after an interruption. No new account was created. You can still sign in to an existing account.',
  accountExistingOnly: 'Existing accounts can sign in and restore their saved keys. New account setup is currently paused.',
  accountWorking: 'Checking your account and saved keys…',
  accountCancel: 'cancel this step',
  identityContinuityMissing: 'This account has no saved recovery record and this tab does not hold its original keys. Signing in again cannot recreate them. No replacement keys will be made.',
} as const

export const journeyRu: Record<keyof typeof journeyEn, string> = {
  scanPreparing: 'Готовим открытку на этом устройстве. Пока ничего не показано.',
  openChecking: 'Проверяем разрешение на открытие. Сохраните подключение или отмените.',
  openDenied: 'Эту открытку больше нельзя открыть. Ничего не показано.',
  openUnconfirmed: 'Не удалось подтвердить открытие. Ничего не показано. Подключитесь к сети и повторите: предыдущий запрос уже мог быть принят.',
  openSignIn: 'Войдите в этот аккаунт снова перед открытием. Ничего не показано.',
  registrationPaused: 'Создание новых аккаунтов временно приостановлено, пока ключи нельзя безопасно восстановить после прерывания. Новый аккаунт не создан. В существующий аккаунт по-прежнему можно войти.',
  accountExistingOnly: 'В существующий аккаунт можно войти и восстановить сохранённые ключи. Создание новых аккаунтов пока приостановлено.',
  accountWorking: 'Проверяем аккаунт и сохранённые ключи…',
  accountCancel: 'отменить этот шаг',
  identityContinuityMissing: 'У этого аккаунта нет сохранённой записи восстановления, а в этой вкладке нет его исходных ключей. Повторный вход не воссоздаст их. Новые ключи не будут созданы.',
}

export const journeyUk: Record<keyof typeof journeyEn, string> = {
  scanPreparing: 'Готуємо листівку на цьому пристрої. Поки нічого не показано.',
  openChecking: 'Перевіряємо дозвіл на відкриття. Збережіть з’єднання або скасуйте.',
  openDenied: 'Цю листівку більше не можна відкрити. Нічого не показано.',
  openUnconfirmed: 'Не вдалося підтвердити відкриття. Нічого не показано. Під’єднайтеся до мережі та повторіть: попередній запит уже міг бути прийнятий.',
  openSignIn: 'Увійдіть у цей акаунт знову перед відкриттям. Нічого не показано.',
  registrationPaused: 'Створення нових акаунтів тимчасово призупинено, доки ключі не можна безпечно відновити після переривання. Новий акаунт не створено. До наявного акаунта й надалі можна увійти.',
  accountExistingOnly: 'До наявного акаунта можна увійти й відновити збережені ключі. Створення нових акаунтів поки призупинено.',
  accountWorking: 'Перевіряємо акаунт і збережені ключі…',
  accountCancel: 'скасувати цей крок',
  identityContinuityMissing: 'Цей акаунт не має збереженого запису відновлення, а ця вкладка не має його початкових ключів. Повторний вхід не відтворить їх. Нових ключів не буде створено.',
}
