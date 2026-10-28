# Olenva

極簡的 Android AI 聊天 App，特色是**在聊天輸入框直接展開相機、自動掃描紙本**，把裁切、校正、增強後的照片放進輸入框，由你補充問題再送出。模型透過 OpenRouter 使用，數學公式以 KaTeX 完整渲染。

## 主要功能

- **聊天**：串流回答、停止／重新生成、編輯重送、引用、複製、分享、查看原始內容；推理模型的思考過程可展開。
- **掃描**：App 內相機（預設超廣角）、即時文件邊界偵測（裝置上的 AI 角點模型，雜亂桌面、斜拍、手拿都抓得到；手機拿得低時會依重力感測器先把畫面轉成俯視再找邊）、畫面穩定後自動拍攝、單頁／多頁（同一頁不會重複拍）、手動模式。
- **影像處理**（OpenCV）：透視校正、文字方向自動轉正（裝置上的 ML Kit OCR）、去陰影、對比與銳化；模式：自動增強（依頁面自動決定白點、黑點與是否保留墨水顏色）／原始彩色。保留原圖，送出前可重新裁切（拖曳四角）、旋轉、切換模式。
- **公式與 Markdown**：公式先從 Markdown 抽出再交給 KaTeX，錯誤公式退回可讀原文（不出現紅字）；串流中未完成的公式先以淡色原文顯示，不閃爍；寬公式、表格、程式碼可水平捲動並有邊緣淡出提示；點公式可複製 LaTeX。
- **OpenRouter**：API Key 以 Android Keystore 加密保存、測試連線；模型清單即時更新（只列近 3 個月推出、支援圖片、推理與工具呼叫、上下文 128K 以上的文字模型（不含 `:batch` 版本），主流開發商排前面（Google、Anthropic、OpenAI、xAI、Meta、Moonshot、DeepSeek、GLM、Qwen、MiniMax、小米），其餘開發商收在預設收起的「其他開發商」、可自行新增模型 ID；搜尋、收藏、預設模型、價格與上下文長度）；送圖給不支援圖片的模型前會攔下並提供切換。
- **用量**：每則回答顯示 token 與 OpenRouter 回報費用（BYOK 會加上上游費用），只有估算時會標「估算」。
- **紀錄**：對話自動命名、搜尋、重新命名、刪除；草稿自動保存；匯出 Markdown、含附件的 zip 備份與匯入。
- **附件**：相簿、檔案（JPG／PNG／WebP／PDF／TXT／MD）、從其他 App 分享進來。
- **閱讀設定**：對話字體大小（含即時預覽，公式一起縮放）、淺色／深色／跟隨系統、介面密度、繁體中文／English。

## 建置

需求：Android SDK（compileSdk 37）、JDK 21（Android Studio 內建 JBR 即可）。

```bash
./gradlew :app:assembleDebug      # app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
./gradlew :app:assembleRelease    # arm64 約 33 MB（目前以 debug key 簽名，上架前需換正式 keystore）
adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

依 ABI 分包（arm64-v8a、x86_64），因為 OpenCV 原生庫很大。

## 測試

```bash
node tools/render-test.js         # Markdown + LaTeX 渲染回歸測試（20 項）
./gradlew :app:testDebugUnitTest   # 文字方向、框線穩定、角點解碼、裁切比例、模型清單
```

## 架構

| 位置 | 內容 |
|---|---|
| `chat/` | `ChatViewModel`（送出、串流、重試、草稿、標題）、`ChatScreen`、`Transcript`（WebView 橋接）、`HistoryDrawer` |
| `scan/` | `ScannerScreen`（CameraX）、`DocAnalyzer`（即時偵測與穩定判斷）、`DocVision`（OpenCV 偵測／校正／增強）、`ScanPipeline`、`AttachmentEditor` |
| `net/OpenRouter.kt` | 模型清單、金鑰檢查、SSE 串流、錯誤分類 |
| `data/` | Room（對話、訊息、草稿）、DataStore 設定、Keystore 金鑰、附件儲存、備份 |
| `settings/` | 設定頁、模型選擇器 |
| `assets/web/` | 對話渲染頁：markdown-it、KaTeX（含 mhchem）、highlight.js、morphdom，全部離線打包 |

第三方模型：文件角點偵測使用 [DocAligner](https://github.com/DocsaidLab/DocAligner)（Apache-2.0，授權見 `third_party/DocAligner/LICENSE`）；為了讓 OpenCV 讀取，已用 `tools/docaligner_to_opencv.py` 改寫部分運算節點，輸出結果不變。

參考專案：[RikkaHub](https://github.com/rikkahub/rikkahub)（同樣以 WebView + KaTeX 渲染公式）。

## 隱私

送出的文字與附件會交由 OpenRouter 及所選模型的提供商處理。掃描完成不會自動送出，照片不會存進公開相簿；API Key 不會出現在匯出或備份中。
