# CONTEXT

- 原生 Kotlin + Jetpack Compose，單一 `app` 模組；手動 DI（`OlenvaApp.AppContainer`），無 Hilt。
- 工具鏈：AGP 9.4.0 / Kotlin 2.4.21 / KSP 2.3.10 / Gradle 9.8.1 / compileSdk 37 / minSdk 26。
- 對話內容一律由 `assets/web/` 的單一 WebView 渲染；Kotlin 端只推送完整訊息 JSON（`Olenva.setMessages`），JS 端逐則比對後用 morphdom 局部更新。改渲染邏輯後跑 `node tools/render-test.js`。
- 掃描影像處理的關鍵假設：背景估計用 closing，再以「附近紙面亮度」區分陰影與實心內容（避免把填色圖表洗白）。增強只剩「自動」與「原始」兩種（2026-10-28 起；舊的彩色／灰階／黑白頁面重新處理時一律走自動）。
- 掃描附件在送出前保留 `originalPath`／`quad` 以便重新處理；送出時刪除原圖。
- 費用：BYOK 時 `usage.cost` 為 0，實際費用在 `cost_details.upstream_inference_cost`，兩者相加後顯示。
- 文字方向：`scan/Orientation.kt` 用 PaddleOCR 的 PP-LCNet_x1_0_doc_ori（`assets/doc_ori_lcnet.onnx`，6.8 MB，官方 ONNX，OpenCV DNN 直接可讀）把裁好的整頁壓成 224×224 判 0/90/180/270，輸出是「頁面目前被順時針轉了幾度」，修正量 = 360 − 該值。不要做中心裁切（裁切版在手寫頁轉 270° 時判錯）。信心門檻 0.8：真正的頁面都 ≥ 0.89，沒裁到紙的桌面照片會到 0.78 且常判反。舊的 ML Kit OCR 方案已移除：它能分直橫，但手寫數學的上下幾乎都判反（實測正向手寫頁報 180°、顛倒頁報 0°），而且一次要 1.5 秒。拍照時先依 `OrientationEventListener`（平放時沿用最後一次讀數）轉成手機當下的方向，模型只在這之上加修正，模型沒把握就維持重力方向；使用者手動旋轉永遠優先（`Attachment.oriented`）（2026-10-29）。
- 拍照：`CAPTURE_MODE_MINIMIZE_LATENCY`，Pixel 6a 上約 0.3 秒（最高畫質模式每張都重新對焦，約 1.7 秒）；偵測到紙或紙移動時對紙張中心對焦。音量鍵當快門（`MainActivity.volumeShutter`，編輯頁開著時停用）。快門音已移除。
- 貼邊：`scan/EdgeSnap.kt` 在模型給的四邊兩側各 4%（長邊）範圍內，沿邊取 60 個剖面找「往紙內變亮」最大的階梯（兩側各平均 6 px，比筆畫寬，所以筆跡不會被當成紙邊），擬合直線後取相鄰兩線交點當新角；某邊找不到清楚的邊（白紙放白桌）就保留模型的角。在 1600 px 灰階圖上跑，約 100 ms。拍照與編輯的自動偵測都會經過它。
- 文件偵測：`DocDetector` 用 DocAligner（Apache-2.0）的 lcnet100 熱圖模型（`assets/docaligner_lcnet100.onnx`，4.7 MB）找四個角，跑在 App 原本就有的 OpenCV DNN（FP16；Pixel 6a 上 2 條執行緒約 50 ms、預設 8 條約 130 ms）。OpenCV 執行緒數只在載入模型時設一次（4 條），絕對不要每次偵測前後切換：切換會重建執行緒池，若拍照處理正在另一條執行緒跑濾波就會 SIGSEGV（2026-10-29 實機當機過）。紙面積門檻 4%（超廣角下紙常只佔一小塊，12% 會擋掉）。模型輸入尺寸寫死 256，不能改小。原始模型 OpenCV 讀不了，轉換用 `tools/docaligner_to_opencv.py`。舊的邊緣輪廓法在 227 張真實照片上四角誤差 <2% 只有 27%，模型 56%，且常出現超出畫面的錯框，已移除。模型在 App 啟動時背景載入，載入前偵測回傳 null。
- 自動增強（`auto`）：背景估計核 邊長/30（其他模式 /18）；白點 = 紙面中位數 − 3σ（225～250，紙面雜訊大就切深、乾淨就保留淡字）；黑點跟著附近墨色走（斜拍時遠端的字較灰），gamma 2；再以紙面平均色去偏色。固定白點 238 曾把斜拍遠端的淡字洗掉。
- 裁切比例：`DocProcessor.aspect` 用 Zhang & He（2007）從四角推回紙的真實長寬比，斜拍不再把直式 A4 壓成橫式；焦距能從四角算出就用，算不出（紙上下邊在照片裡平行）時假設 0.55 × 長邊（預設 0.6× 廣角實測 0.51～0.53）。
- 低角度：曾試過依重力感測器把預覽轉成俯視再偵測，模擬顯示多抓到的框大多是錯的（斜 50° 時錯框 3% → 21%），已移除；錯框比沒框糟。
- 即時框線：`scan/QuadTracker.kt` 負責穩定（小抖動取平均、離目前框很遠的偵測要連續出現 3 次才跟、偶爾漏一幀保留 400 ms）。穩定判斷與自動拍攝都用平滑後的框；框在 200 ms 內移動 < 2.5% 算穩定，再穩 300 ms 就拍（原本 300＋500 ms，使用者嫌慢）。改偵測參數前先跑 `QuadTrackerTest`。
- 側邊欄：`ModalNavigationDrawer` 關閉時停用手勢（它的拖曳會搶 WebView 的上下捲動），改由 `chat.js` 偵測「快速的向右甩」呼叫 `Android.openDrawer()`（140 ms 內向右 100 CSS px、上下偏移 ≤ 24 px）；起點在可橫向捲動的公式／表格／程式碼上時一律不觸發。
- 模型選單：只列 `created` 在 92 天內、支援圖片輸入、`supported_parameters` 含 reasoning 與 tools、上下文 ≥128K、輸出含 text 且不含 image、id 不以 `:batch` 結尾的模型（2026-10-28 起，「支援圖片」篩選鈕已拿掉）；依 id 前綴分組，主流開發商依 `LAB_ORDER` 排前，其餘 A→Z 收在「其他開發商」摺疊區（預設收起，搜尋時自動展開）。自訂 ID、收藏不受這些限制；「最近使用」已移除（2026-10-28）。
- 未完成：P2 全部；多頁掃描中途被系統殺掉時，尚未加入輸入框的頁面不會恢復；release 仍用 debug key 簽名。
- 日常使用裝 release（`assembleRelease`，R8＋baseline profile）：冷啟動 ~0.3 s，debug ~1.2 s。兩者同簽章，可直接覆蓋安裝、保留資料。
