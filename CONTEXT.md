# CONTEXT

- 原生 Kotlin + Jetpack Compose，單一 `app` 模組；手動 DI（`OlenvaApp.AppContainer`），無 Hilt。
- 工具鏈：AGP 9.4.0 / Kotlin 2.4.21 / KSP 2.3.10 / Gradle 9.8.1 / compileSdk 37 / minSdk 26。
- 對話內容一律由 `assets/web/` 的單一 WebView 渲染；Kotlin 端只推送完整訊息 JSON（`Olenva.setMessages`），JS 端逐則比對後用 morphdom 局部更新。改渲染邏輯後跑 `node tools/render-test.js`。
- 掃描影像處理的關鍵假設：背景估計用 closing，再以「附近紙面亮度」區分陰影與實心內容（避免把填色圖表洗白）。增強只剩「自動」與「原始」兩種（2026-10-28 起；舊的彩色／灰階／黑白頁面重新處理時一律走自動）。
- 掃描附件在送出前保留 `originalPath`／`quad` 以便重新處理；送出時刪除原圖。
- 費用：BYOK 時 `usage.cost` 為 0，實際費用在 `cost_details.upstream_inference_cost`，兩者相加後顯示。
- 文字方向：`scan/Orientation.kt` 用 ML Kit 中文 OCR（Play services 版，模型由 Play services 下載、App 啟動時預先要求）跑一次，依各行文字角度投票決定 0/90/180/270，只計信心值 ≥ 0.5 的字（手寫數學會被讀成亂碼且角度報 180°，曾把正向講義轉反）；ML Kit 會直接讀旋轉文字，所以「四個方向各跑一次比分數」無效。模型尚未就緒時回傳 null，之後編輯會重試；使用者手動旋轉永遠優先（`Attachment.oriented`）。
- 文件偵測：`DocDetector` 用 DocAligner（Apache-2.0）的 lcnet100 熱圖模型（`assets/docaligner_lcnet100.onnx`，4.7 MB）找四個角，跑在 App 原本就有的 OpenCV DNN（FP16、固定 2 條執行緒；Pixel 6a 上 2 條約 50 ms、預設 8 條約 130 ms）。紙面積門檻 4%（超廣角下紙常只佔一小塊，12% 會擋掉）。模型輸入尺寸寫死 256，不能改小。原始模型 OpenCV 讀不了，轉換用 `tools/docaligner_to_opencv.py`。舊的邊緣輪廓法在 227 張真實照片上四角誤差 <2% 只有 27%，模型 56%，且常出現超出畫面的錯框，已移除。模型在 App 啟動時背景載入，載入前偵測回傳 null。
- 自動增強（`auto`）：背景估計核 邊長/30（其他模式 /18）；白點 = 紙面中位數 − 3σ（225～250，紙面雜訊大就切深、乾淨就保留淡字）；黑點跟著附近墨色走（斜拍時遠端的字較灰），gamma 2；再以紙面平均色去偏色。固定白點 238 曾把斜拍遠端的淡字洗掉。
- 裁切比例：`DocProcessor.aspect` 用 Zhang & He（2007）從四角推回紙的真實長寬比，斜拍不再把直式 A4 壓成橫式；焦距能從四角算出就用，算不出（紙上下邊在照片裡平行）時假設 0.55 × 長邊（預設 0.6× 廣角實測 0.51～0.53）。
- 低角度：曾試過依重力感測器把預覽轉成俯視再偵測，模擬顯示多抓到的框大多是錯的（斜 50° 時錯框 3% → 21%），已移除；錯框比沒框糟。
- 即時框線：`scan/QuadTracker.kt` 負責穩定（小抖動取平均、離目前框很遠的偵測要連續出現 3 次才跟、偶爾漏一幀保留 400 ms）。穩定判斷與自動拍攝都用平滑後的框；改偵測參數前先跑 `QuadTrackerTest`。
- 側邊欄：`ModalNavigationDrawer` 關閉時停用手勢（它的拖曳會搶 WebView 的上下捲動），改由 `chat.js` 偵測「快速的向右甩」呼叫 `Android.openDrawer()`（140 ms 內向右 100 CSS px、上下偏移 ≤ 24 px）；起點在可橫向捲動的公式／表格／程式碼上時一律不觸發。
- 模型選單：只列 `created` 在 92 天內、支援圖片輸入、`supported_parameters` 含 reasoning 與 tools、上下文 ≥128K、輸出含 text 且不含 image、id 不以 `:batch` 結尾的模型（2026-10-28 起，「支援圖片」篩選鈕已拿掉）；依 id 前綴分組，主流開發商依 `LAB_ORDER` 排前，其餘 A→Z 收在「其他開發商」摺疊區（預設收起，搜尋時自動展開）。自訂 ID、收藏不受這些限制；「最近使用」已移除（2026-10-28）。
- 未完成：P2 全部；多頁掃描中途被系統殺掉時，尚未加入輸入框的頁面不會恢復；release 仍用 debug key 簽名。
