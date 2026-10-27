# CONTEXT

- 原生 Kotlin + Jetpack Compose，單一 `app` 模組；手動 DI（`OlenvaApp.AppContainer`），無 Hilt。
- 工具鏈：AGP 9.4.0 / Kotlin 2.4.21 / KSP 2.3.10 / Gradle 9.8.1 / compileSdk 37 / minSdk 26。
- 對話內容一律由 `assets/web/` 的單一 WebView 渲染；Kotlin 端只推送完整訊息 JSON（`Olenva.setMessages`），JS 端逐則比對後用 morphdom 局部更新。改渲染邏輯後跑 `node tools/render-test.js`。
- 掃描影像處理的關鍵假設：背景估計用 closing，再以「附近紙面亮度」區分陰影與實心內容（避免把填色圖表洗白）；黑白模式＝自適應門檻 AND 全域門檻（避免實心區變空心）。
- 掃描附件在送出前保留 `originalPath`／`quad` 以便重新處理；送出時刪除原圖。
- 費用：BYOK 時 `usage.cost` 為 0，實際費用在 `cost_details.upstream_inference_cost`，兩者相加後顯示。
- 文字方向：`scan/Orientation.kt` 用 ML Kit 中文 OCR（Play services 版，模型由 Play services 下載、App 啟動時預先要求）跑一次，依各行文字角度投票決定 0/90/180/270；ML Kit 會直接讀旋轉文字，所以「四個方向各跑一次比分數」無效。模型尚未就緒時回傳 null，之後編輯會重試；使用者手動旋轉永遠優先（`Attachment.oriented`）。
- 未完成：P2 全部；多頁掃描中途被系統殺掉時，尚未加入輸入框的頁面不會恢復；release 仍用 debug key 簽名。
