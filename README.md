# webcal-cmp-push

Calendar notifications and ITELA weekly meal-menu notifications.

## Menu import

- At **06:00** and **18:00** (Europe/Luxembourg), check for a weekly menu if that week is not already cached.
- At **10:00**, use today's cached menu, or import it if missing, then send one notification to `ntfy.menu` if at least one meal exists. No message is sent for an empty day.
- From **Friday 18:00** through Sunday, look for **next week's** menu instead of the current week's menu.
- The importer looks through recent Facebook feed photos, accepting an image only if OCR recognizes `menu.title` AND the printed Monday–Friday date range corresponds to the desired week. Date-stamped posts before the previous Friday are excluded.
- The image layout is split by proportional cell coordinates into five weekdays and two meals (`MIDI`/`SOUPER`). OCR runs on each cell separately. Missing entries and phrases like `Pas de repas` / `Bon week-end` become SQL `NULL`.
- `MenuData` contains one database row per date, with nullable `midi` and `souper` columns; `MealData` contains the meal type when processing OCR results. Once all five date rows exist, OCR and Facebook requests stop for that week.

## Configuration

`src/main/resources/application.yml` contains these settings:

| YAML setting | Environment variable | Default |
| --- | --- | --- |
| `menu.facebook` | `MENU_FACEBOOK` | `https://www.facebook.com/itela.be/` |
| `menu.title` | `MENU_TITLE` | `MENU DE LA SEMAINE` |
| `menu.cookies` | `MENU_FACEBOOK_COOKIES` | empty |
| `menu.ocr.executable` | `MENU_OCR_EXECUTABLE` (Spring environment binding) | `tesseract` |
| `menu.ocr.language` | `MENU_OCR_LANGUAGE` (Spring environment binding) | `fra+eng` |
| `ntfy.menu` | `NTFY_MENU` | empty (notification disabled) |

For a Facebook session, set `MENU_FACEBOOK_COOKIES` to the raw Cookie header copied from your **own authorized browser session**, e.g. `c_user=...; xs=...`. Keep it only in environment secrets, never in the repository or logs. The importer won't solve captchas or evade an account block. Facebook may change its markup, and cookies can expire. If the feed is not accessible, the refresh logs an error and leaves existing DB rows untouched.

The Docker image contains Chromium and French/English Tesseract. For manual development outside Docker, install Java 21, Maven, Playwright's Chromium (`mvn exec:java -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium"`) and the Tesseract French and English language data.

**Schema note:** The initial `MenuData.text` column was replaced by `midi` and `souper`. If you already have a SQLite database from the old prototype, migrate its menu table before deploying. Do not delete your calendar database to make this change.

## Testing

Run `mvn test`. `MenuOcrServiceTest` includes the supplied sample menu image and verifies that lunch, supper, and no-meal cells are handled correctly. Its integration test runs when `tesseract` is installed; the other unit tests are independent of OCR. A live Facebook check requires an accessible session and must be tested against the real page separately.
