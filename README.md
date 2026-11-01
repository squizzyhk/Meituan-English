# MT English

MT English is an Android app that lets English speakers use Meituan (美团), China's big food delivery and local services platform, in English.

It is a separate, independent app. It does not change, copy or redistribute the official Meituan app. It translates what Meituan shows you into natural English, written the way a food delivery app like Uber Eats or Deliveroo would say it. Some examples:

| Chinese on Meituan | What MT English shows |
|---|---|
| 月售1000+ | 1,000+ sold/month |
| 起送¥20 | ¥20 min. order |
| 配送费¥3 | ¥3 delivery |
| 人均¥35 | ¥35 per person |
| 满30减5 | ¥5 off orders over ¥30 |
| 宫保鸡丁 | Kung Pao Chicken |
| 微辣 | Mild spicy |
| 去结算 | Checkout |

It translates buttons and menus, store names and details, dish names and descriptions, forms (addresses, sign-in, checkout), reviews, and Chinese text inside pictures such as menu photos and promotion banners.

## The two ways to use it

### 1. The built-in English browser (easiest)

Open MT English and tap one of the tiles: **Food Delivery**, **Meituan Home**, **Restaurants & Reviews** (Dianping) or **Hotels**. This opens Meituan's own mobile website inside MT English, and the whole page is translated as it loads, including new content that appears as you scroll.

You can also paste a Meituan or Dianping link (for example one a friend shared in WeChat) into the box on the home screen and tap Go.

Tips:
- **Long-press** any translated text to see the original Chinese. You can turn this off in Settings.
- Use **Show original** in the browser menu to see the untouched Chinese page.

### 2. Live labels over the official apps

If you prefer the official Meituan, Meituan Waimai (美团外卖) or Dianping (大众点评) apps, MT English can put English labels on top of their screens while you use them. A small floating bubble lets you hide or show the labels, and on Android 11 or newer it has a camera button that translates the pictures currently on screen.

This mode uses an Android "Accessibility" permission, which you have to switch on yourself (see below).

## Installing the APK

1. Download the file `app-release.apk` to your Android phone (Android 8.0 or newer).
2. Tap the file to open it.
3. Android will probably say that installing apps from this source is blocked. Tap **Settings**, turn on **Allow from this source** for the app you used to open the file (for example Chrome or Files), then go back.
4. Tap **Install**.

If you get the APK from GitHub: open the repository's **Actions** tab, pick the latest successful run, and download the **MT-English-apk** artifact. It arrives as a .zip file; unzip it to get the .apk.

## Better translations with a Claude API key (optional)

MT English works straight away without any account. Out of the box it uses:
- a built-in dictionary of about 6,500 common Meituan words and phrases (menus, dishes, buttons, forms, promotions), plus rules for prices, quantities and dates, and
- Google's free offline translation model, which runs on your phone.

That covers the common screens well, but anything outside the dictionary (unusual dish names, store descriptions, reviews) will be translated by the offline model and will read more like machine translation.

For much more natural English, add a key for Claude, Anthropic's AI:

1. Go to **console.anthropic.com** and create an account.
2. Add a payment method or credits under **Billing**. You pay Anthropic directly for what you use; translated text is saved on your phone, so the same text is never paid for twice.
3. Open **API Keys**, tap **Create Key**, and copy the key (it starts with `sk-ant-`).
4. In MT English, open **Settings** (the gear icon), tap **Claude API key**, paste the key and tap OK.
5. Tap **Test API key** to check it works.

To get the offline model ready before you need it (for example before going somewhere without mobile data), tap **Download offline translation model** on the home screen or in Settings. It is a one-time download of about 30 MB.

## Turning on the live labels

1. Open MT English. The home screen shows three numbered steps for live labels.
2. Tap the button to open Android's **Accessibility** settings.
3. Find **MT English live translation** (it may be under **Installed apps** or **Downloaded apps**), turn it on, and confirm.
4. Open the official Meituan, Meituan Waimai or Dianping app. English labels appear over the Chinese text, and the floating bubble appears at the edge of the screen.

To stop it, turn **Live labels in official apps** off in MT English's Settings, or switch the service off again in Android's Accessibility settings.

## Limitations

- **Signing in:** Meituan usually asks you to sign in with a text message (SMS) code. This may need a mainland China (+86) or Hong Kong phone number. MT English cannot get around this.
- **Payment pages:** paying often hands you over to WeChat Pay or Alipay. Those screens belong to other apps and may stay in Chinese.
- **Live label alignment:** in the official apps, English labels are drawn on top of the Chinese text. English is often longer than Chinese, so labels may be shrunk, cut short with "…", or not line up perfectly. Some screens the app draws as pictures cannot be read at all except with the camera button.
- **Pictures:** text inside images is found with on-device text recognition. Stylised fonts, small text or busy backgrounds may be missed or translated imperfectly, and the redrawn English will not match the original design exactly.
- **Website changes:** the browser mode depends on Meituan's mobile websites. If Meituan changes or limits them, some pages may stop working until the app is updated. Some features only exist in the official apps.
- **Without a Claude key**, uncommon text is translated by the offline model and reads less naturally.

## Privacy

- Text from the pages and screens you view is sent to **Anthropic's API** for translation **only if you have added a Claude API key**. It is sent directly from your phone to Anthropic using your key.
- Without a key, all translation happens on your phone.
- The only other thing MT English downloads is Google's offline translation model (and the text recognition data that comes with it). No text is sent to Google for this.
- MT English has no accounts, no analytics and no ads. Translations are cached on your phone, and you can clear them in Settings.
- **Live labels:** the Accessibility service only reads screens of the Meituan, Meituan Waimai and Dianping apps. It never reads password fields or what you type. On Android 11 and newer it takes screenshots of those apps to match label colours and to translate pictures; screenshots stay in memory and are discarded immediately, never saved.
- When you use Meituan's websites in the browser mode, you are of course talking to Meituan, the same as in any browser.

## Disclaimer

MT English is an independent, unofficial project. It is not affiliated with, endorsed by or sponsored by Meituan, Dianping, Anthropic or Google. "Meituan" and "Dianping" are trademarks of their owners and are used here only to describe what the app works with. Translations may contain mistakes; check important details such as prices, allergens and addresses against the original.

## For developers

Requirements: JDK 17 or newer and the Android SDK (compileSdk 35). Put `sdk.dir=/path/to/android-sdk` in `local.properties` or set `ANDROID_HOME`.

```bash
./gradlew assembleDebug           # debug APK -> app/build/outputs/apk/debug/
./gradlew assembleRelease         # release APK (signed with the debug key) -> app/build/outputs/apk/release/
./gradlew testDebugUnitTest       # JVM unit tests (includes the glossary validator)
./gradlew lintDebug               # Android lint
cd tools/js-test && npm test      # headless Chromium tests for the page translator script (needs Playwright)
```

Layout of the code (`app/src/main/java/com/mtenglish/app/`):

- `translate/` - the translation engine: glossary and pattern rules, in-memory and SQLite cache, Claude (official Java SDK), ML Kit offline fallback.
- `web/` - the English browser (WebView, bridge, image proxy) and `assets/inject/translator.js`, the script that translates pages in place.
- `image/` - OCR of Chinese text in images and re-rendering in English.
- `overlay/` - the Accessibility service that draws English labels over the official apps.
- `ui/` - home screen and Settings.
- `assets/glossary/*.tsv` - the dictionary, one `chinese<TAB>english` entry per line.

GitHub Actions (`.github/workflows/android.yml`) builds the release APK, runs the unit tests and uploads the APK as the `MT-English-apk` artifact on every push and pull request.
