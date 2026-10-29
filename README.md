# Olenva

拍下紙本題目、直接問 AI 的 Android 聊天 App。

在輸入框旁按「掃描」就會打開相機，自動找出紙張、拍照、裁切、轉正、讓字更清楚，照片放進輸入框後再補上你的問題送出。模型透過 [OpenRouter](https://openrouter.ai) 使用，數學公式以 KaTeX 完整渲染。

## 下載安裝

到 [Releases](../../releases/latest) 下載 `Olenva-<版本>-arm64.apk`，在手機上開啟安裝。

- 需要 Android 8.0 以上、64 位元 ARM 手機（近幾年的手機都是）。
- 第一次安裝時系統會要你允許「安裝不明來源的應用程式」。
- 安裝後到「設定」貼上 OpenRouter API Key（沒有的話到 [openrouter.ai/keys](https://openrouter.ai/keys) 建立）。

## 功能

### 掃描紙本

- App 內相機，預設超廣角；音量鍵當快門、不播快門音。
- 即時找出紙張四角（裝置上的 AI 模型，雜亂桌面、斜拍、手拿都抓得到），畫面穩定後自動拍；也可切手動。
- 單頁或多頁連拍，同一頁不會重複拍。
- 自動處理：透視校正、四邊貼齊紙邊、依真實比例裁切、自動轉正（看版面判斷上下，手寫也適用）。
- 自動增強：去陰影、提高對比與清晰度，淡的筆畫加深、密集的中文字不黏在一起；筆跡保留原本顏色。
- 送出前可重新裁切（拖曳四角）、旋轉，或用開關切回原始色彩；原圖會保留到送出為止。

### 聊天

- 串流回答、停止、重新生成、編輯重送、引用、複製、分享、查看原始內容。
- 思考過程預設收合，推理時顯示秒數；切到其他畫面時可繼續產生回覆。
- **思考強度**：長按輸入框的「大腦」按鈕，左右滑動選低／中／高（預設高），放開就套用。
- 回覆長度上限直接用模型支援的最大值，不用自己設。
- 公式、表格、程式碼可水平捲動；點公式可複製 LaTeX；錯誤公式退回可讀原文，不出現紅字。
- 每則回答顯示 token 數與 OpenRouter 回報的費用。

### 模型

- 模型清單即時更新：只列近 3 個月推出、能看圖、會推理、支援工具呼叫、上下文 128K 以上的模型。
- 主流開發商排前面，其餘收在「其他開發商」；可搜尋、收藏、設預設，也能手動加模型 ID 或 OpenRouter Preset。
- 送圖給不能看圖的模型前會先攔下，提供切換。

### 其他

- 對話紀錄：自動命名、搜尋、重新命名、刪除；側邊欄往左一滑就收起。
- 草稿自動保存；可匯出 Markdown、含附件與設定的 zip 備份並匯入（可選擇連 API Key 一起備份）。
- 附件：相簿、檔案（JPG／PNG／WebP／PDF／TXT／MD）、從其他 App 分享進來或貼上圖片。含圖片的訊息可複製圖片，文字可從「更多 → 複製全文」複製。
- 所有按鈕、選單、開關都有震動回饋（跟著系統「觸碰回饋」開關）。
- 對話字體大小（含即時預覽）、淺色／深色／跟隨系統、繁體中文／English。

## 隱私

- 送出的文字與附件會交給 OpenRouter 及所選模型的提供商處理。
- 掃描完不會自動送出，照片不會存進手機相簿。
- API Key 以 Android Keystore 加密保存。只有在「匯出全部資料」時勾選「包含 API Key」才會放進備份檔（明文），方便換手機或重裝；這種備份檔不要分享。
- 文件偵測與轉正都在手機上跑，不會上傳。

## 自己建置

需要 Android SDK（compileSdk 37）與 JDK 21（Android Studio 內建的 JBR 即可）。

```bash
./gradlew :app:assembleRelease   # app/build/outputs/apk/release/app-arm64-v8a-release.apk
./gradlew :app:assembleDebug     # 開發用，啟動較慢
adb install -r app/build/outputs/apk/release/app-arm64-v8a-release.apk
```

- 依 CPU 架構分包（arm64-v8a、x86_64），因為 OpenCV 原生庫很大。
- arm64 的 OpenCV 原生庫是精簡版，只含 App 用到的 core、imgproc、dnn（14 MB，官方完整版 24 MB），放在 `app/src/main/jniLibs/`，用 `tools/build_opencv_slim.sh` 重編；Java 類別與 x86_64 版仍來自 Maven 的 `org.opencv:opencv`，兩邊版本要一起升。
- release 的簽名金鑰從環境變數讀取：`OLENVA_KEYSTORE`（金鑰檔路徑）、`OLENVA_KEYSTORE_PASSWORD`、`OLENVA_KEY_ALIAS`（預設 `olenva`）。沒設定時改用本機的 debug key，照樣能建置安裝，但無法覆蓋安裝 Releases 上的版本（簽名不同）。

## 測試

```bash
./gradlew :app:testDebugUnitTest   # 文件方向、貼邊、框線穩定、角點解碼、裁切比例、模型清單
node tools/render-test.js          # Markdown + LaTeX 渲染回歸測試
```

## 程式架構

原生 Kotlin + Jetpack Compose，單一 `app` 模組。

| 位置 | 內容 |
|---|---|
| `chat/` | `ChatViewModel`（送出、串流、重試、草稿、標題）、`ChatScreen`（輸入框、思考強度）、`Transcript`（WebView 橋接）、`HistoryDrawer` |
| `scan/` | `ScannerScreen`（CameraX）、`DocAnalyzer`（即時偵測）、`QuadTracker`（框線穩定）、`EdgeSnap`（貼邊）、`DocVision`（OpenCV 校正與增強）、`Orientation`（轉正）、`ScanPipeline`、`AttachmentEditor` |
| `net/OpenRouter.kt` | 模型清單、金鑰檢查、SSE 串流、錯誤分類 |
| `data/` | Room（對話、訊息、草稿）、DataStore 設定、Keystore 金鑰、附件儲存、備份 |
| `settings/` | 設定頁、模型選擇器 |
| `ui/` | 主題、震動回饋 |
| `assets/web/` | 對話渲染頁：markdown-it、KaTeX（含 mhchem）、highlight.js、morphdom，全部離線打包 |

開發筆記（設計理由、踩過的坑）在 [`CONTEXT.md`](CONTEXT.md)。

## 第三方元件

| 元件 | 用途 | 授權 |
|---|---|---|
| [DocAligner](https://github.com/DocsaidLab/DocAligner) lcnet100 | 找紙張四角；用 `tools/docaligner_to_opencv.py` 改寫部分運算節點讓 OpenCV 能讀，輸出不變 | Apache-2.0（`third_party/DocAligner/LICENSE`） |
| PaddleOCR [PP-LCNet_x1_0_doc_ori](https://huggingface.co/PaddlePaddle/PP-LCNet_x1_0_doc_ori_onnx) | 判斷頁面上下方向；官方 ONNX，權重用 `tools/onnx_fp16_weights.py` 改存半精度 | Apache-2.0（`third_party/PP-LCNet_doc_ori/LICENSE`） |
| [OpenCV](https://opencv.org) | 影像處理、執行上面兩個模型 | Apache-2.0 |
| KaTeX、markdown-it、highlight.js、morphdom | 對話渲染 | MIT／BSD-3-Clause |

參考專案：[RikkaHub](https://github.com/rikkahub/rikkahub)（同樣以 WebView + KaTeX 渲染公式）。
