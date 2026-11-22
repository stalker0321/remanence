// A route's link, the way the current shell offers it. Wide screens keep
// every route in the top menu. Phones have tabs for home, history and
// settings only: "open" and "make" live on the home cards, and a signed-out
// phone has no menu at all. So take the link that is shown, going home first
// when this page shows none.
export async function follow(page, path) {
  await page.locator('a.mark').waitFor()
  const link = page.locator(`a[href="${path}"]:visible`).first()
  if (await link.count() === 0) await page.locator('a.mark').click()
  await link.click()
}
