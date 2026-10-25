package com.example.appstoredeals

import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread

data class DealItem(
    val title: String,
    val subtitle: String,
    val link: String,
    val trackId: Long = 0L,
    val currentPrice: Double = -1.0
)

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var listDeals: ListView
    private lateinit var etSearch: EditText
    private val currentItems = mutableListOf<DealItem>()
    private lateinit var listAdapter: ArrayAdapter<String>
    private val displayStrings = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tv_status)
        listDeals = findViewById(R.id.list_deals)
        etSearch = findViewById(R.id.et_search_app)

        listAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, displayStrings)
        listDeals.adapter = listAdapter

        createNotificationChannel()
        initDefaultWatchlist()

        // 點擊列表項目直接跳轉 App Store 或情報網頁
        listDeals.setOnItemClickListener { _, _, position, _ ->
            val item = currentItems.getOrNull(position) ?: return@setOnItemClickListener
            if (item.link.isNotEmpty()) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.link)))
            }
        }

        // 長按列表項目可從追蹤清單移除
        listDeals.setOnItemLongClickListener { _, _, position, _ ->
            val item = currentItems.getOrNull(position) ?: return@setOnItemLongClickListener true
            if (item.trackId > 0) {
                AlertDialog.Builder(this)
                    .setTitle("移除追蹤")
                    .setMessage("確定要將「${item.title}」從追蹤清單移除嗎？")
                    .setPositiveButton("移除") { _, _ ->
                        removeFromWatchlist(item.trackId)
                        loadWatchlistPrices()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            true
        }

        findViewById<Button>(R.id.btn_tab_free).setOnClickListener {
            loadDailyFreeDeals()
        }

        findViewById<Button>(R.id.btn_tab_watchlist).setOnClickListener {
            loadWatchlistPrices()
        }

        findViewById<Button>(R.id.btn_search_add).setOnClickListener {
            val query = etSearch.text.toString().trim()
            if (query.isEmpty()) {
                Toast.makeText(this, "請輸入要查詢的 App 名稱", Toast.LENGTH_SHORT).show()
            } else {
                searchAppStoreAndPrompt(query)
            }
        }

        // 預設載入今日限時免費情報
        loadDailyFreeDeals()
    }

    private fun initDefaultWatchlist() {
        val prefs = getSharedPreferences("watchlist", Context.MODE_PRIVATE)
        if (!prefs.contains("apps_json")) {
            // 預先內建幾個知名付費 App 作為範例 (可隨時長按刪除或新增)
            val defaultArr = JSONArray().apply {
                put(JSONObject().put("trackId", 1444383602L).put("name", "Goodnotes 6").put("lastPrice", 0.0))
                put(JSONObject().put("trackId", 424293657L).put("name", "LumaFusion").put("lastPrice", 990.0))
                put(JSONObject().put("trackId", 936243218L).put("name", "Forest 專注森林").put("lastPrice", 130.0))
            }
            prefs.edit().putString("apps_json", defaultArr.toString()).apply()
        }
    }

    // 1. 抓取 Reddit r/apphookup 與 App Store 最新 iOS 限免情報
    private fun loadDailyFreeDeals() {
        tvStatus.text = "🔄 正在從全球限免情報源抓取最新 Apple Store 限時免費軟體..."
        thread {
            val fetched = mutableListOf<DealItem>()
            try {
                val url = URL("https://www.reddit.com/r/apphookup/new/.rss")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppStoreDealsRadar/1.0")
                    connectTimeout = 10000
                    readTimeout = 10000
                }
                val xmlStr = conn.inputStream.bufferedReader().readText()
                val factory = XmlPullParserFactory.newInstance()
                val parser = factory.newPullParser()
                parser.setInput(StringReader(xmlStr))

                var eventType = parser.eventType
                var insideEntry = false
                var title = ""
                var link = ""

                while (eventType != XmlPullParser.END_DOCUMENT) {
                    val tagName = parser.name
                    when (eventType) {
                        XmlPullParser.START_TAG -> {
                            if (tagName.equals("entry", true) || tagName.equals("item", true)) {
                                insideEntry = true
                                title = ""
                                link = ""
                            } else if (insideEntry && tagName.equals("title", true)) {
                                title = parser.nextText()
                            } else if (insideEntry && tagName.equals("link", true)) {
                                val href = parser.getAttributeValue(null, "href")
                                link = href ?: parser.nextText()
                            }
                        }
                        XmlPullParser.END_TAG -> {
                            if (tagName.equals("entry", true) || tagName.equals("item", true)) {
                                insideEntry = false
                                // 篩選包含 iOS / iPadOS / Mac / Apple 的限免或特價貼文
                                if (title.contains("iOS", true) ||
                                    title.contains("Mac", true) ||
                                    title.contains("Apple", true) ||
                                    title.contains("Free", true) ||
                                    title.contains("$0", true)
                                ) {
                                    val badge = if (title.contains("$0") || title.contains("Free", true)) "🎁 [限時免費]" else "🏷️ [特價情報]"
                                    fetched.add(DealItem("$badge $title", "點擊前往領取/查看詳情", link))
                                }
                            }
                        }
                    }
                    eventType = parser.next()
                }
            } catch (e: Exception) {
                fetched.add(DealItem("⚠️ 無法連線至外部情報源 (${e.localizedMessage})", "請點選右上方「我的追蹤清單」直接查詢 Apple 官方 API", ""))
            }

            runOnUiThread {
                currentItems.clear()
                currentItems.addAll(fetched)
                displayStrings.clear()
                for (item in currentItems) {
                    displayStrings.add("${item.title}
👉 ${item.subtitle}")
                }
                listAdapter.notifyDataSetChanged()
                tvStatus.text = "✅ 已更新最新 Apple 限免與特價情報 (共 ${fetched.size} 筆，點擊可直接開啟)"
            }
        }
    }

    // 2. 透過 Apple 官方 iTunes Lookup API 即時查詢追蹤清單內的台灣區價格
    private fun loadWatchlistPrices() {
        val prefs = getSharedPreferences("watchlist", Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString("apps_json", "[]"))
        if (arr.length() == 0) {
            tvStatus.text = "目前追蹤清單為空，請在上方輸入軟體名稱加入！"
            currentItems.clear()
            displayStrings.clear()
            listAdapter.notifyDataSetChanged()
            return
        }

        tvStatus.text = "🔄 正在連線 Apple 官方伺服器 (iTunes API 台灣區) 檢查最新售價..."
        thread {
            val ids = mutableListOf<Long>()
            val lastPriceMap = mutableMapOf<Long, Double>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.getLong("trackId")
                ids.add(id)
                lastPriceMap[id] = obj.optDouble("lastPrice", -1.0)
            }

            val idParam = ids.joinToString(",")
            val fetched = mutableListOf<DealItem>()

            try {
                val apiUrl = "https://itunes.apple.com/lookup?id=$idParam&country=tw"
                val jsonText = URL(apiUrl).readText()
                val root = JSONObject(jsonText)
                val results = root.getJSONArray("results")

                for (i in 0 until results.length()) {
                    val item = results.getJSONObject(i)
                    val trackId = item.getLong("trackId")
                    val name = item.getString("trackName")
                    val price = item.optDouble("price", 0.0)
                    val formattedPrice = item.optString("formattedPrice", "NT$ $price")
                    val storeUrl = item.optString("trackViewUrl", "")
                    val oldPrice = lastPriceMap[trackId] ?: price

                    val statusTag = when {
                        price == 0.0 -> "🎉 【目前免費 Free！】"
                        oldPrice > 0 && price < oldPrice -> "🔥 【降價特價中！(原 NT$$oldPrice -> $formattedPrice)】"
                        else -> "💰 目前售價：$formattedPrice"
                    }

                    if (price == 0.0 && oldPrice > 0.0) {
                        sendDealNotification("🎁 限免警報：$name", "原本 NT$$oldPrice，現在變成限時免費 ($0)！點此快去領取！", storeUrl)
                    } else if (oldPrice > 0.0 && price < oldPrice) {
                        sendDealNotification("📉 降價通知：$name", "價格從 NT$$oldPrice 降至 $formattedPrice！", storeUrl)
                    }

                    fetched.add(
                        DealItem(
                            title = "$name  —  $statusTag",
                            subtitle = "點擊直接開啟 Apple App Store 頁面 (長按可移除追蹤)",
                            link = storeUrl,
                            trackId = trackId,
                            currentPrice = price
                        )
                    )
                }
            } catch (e: Exception) {
                fetched.add(DealItem("⚠️ 查詢 Apple 官方 API 失敗：${e.localizedMessage}", "", ""))
            }

            runOnUiThread {
                currentItems.clear()
                currentItems.addAll(fetched)
                displayStrings.clear()
                for (item in currentItems) {
                    displayStrings.add("${item.title}
👉 ${item.subtitle}")
                }
                listAdapter.notifyDataSetChanged()
                tvStatus.text = "⭐ 我的追蹤清單已更新 (點擊跳轉 App Store，長按可刪除)"
            }
        }
    }

    // 3. 搜尋 Apple App Store 並加入願望清單
    private fun searchAppStoreAndPrompt(keyword: String) {
        tvStatus.text = "🔍 正在 Apple App Store (台灣區) 搜尋「$keyword」..."
        thread {
            try {
                val encoded = URLEncoder.encode(keyword, "UTF-8")
                val searchUrl = "https://itunes.apple.com/search?term=$encoded&country=tw&entity=software&limit=8"
                val jsonText = URL(searchUrl).readText()
                val results = JSONObject(jsonText).getJSONArray("results")

                if (results.length() == 0) {
                    runOnUiThread {
                        Toast.makeText(this, "找不到相關 iOS 軟體", Toast.LENGTH_SHORT).show()
                        tvStatus.text = "找不到「$keyword」的相關結果"
                    }
                    return@thread
                }

                val names = Array(results.length()) { i ->
                    val obj = results.getJSONObject(i)
                    val title = obj.getString("trackName")
                    val price = obj.optString("formattedPrice", "免費")
                    val dev = obj.optString("sellerName", "")
                    "$title ($price) - $dev"
                }

                runOnUiThread {
                    tvStatus.text = "請選擇要加入價格追蹤的 App："
                    AlertDialog.Builder(this)
                        .setTitle("選擇要追蹤特價/限免的 App")
                        .setItems(names) { _, which ->
                            val chosen = results.getJSONObject(which)
                            addToWatchlist(
                                chosen.getLong("trackId"),
                                chosen.getString("trackName"),
                                chosen.optDouble("price", 0.0)
                            )
                            loadWatchlistPrices()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "搜尋失敗: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun addToWatchlist(trackId: Long, name: String, price: Double) {
        val prefs = getSharedPreferences("watchlist", Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString("apps_json", "[]"))
        for (i in 0 until arr.length()) {
            if (arr.getJSONObject(i).getLong("trackId") == trackId) {
                Toast.makeText(this, "「$name」已經在追蹤清單中囉！", Toast.LENGTH_SHORT).show()
                return
            }
        }
        arr.put(JSONObject().put("trackId", trackId).put("name", name).put("lastPrice", price))
        prefs.edit().putString("apps_json", arr.toString()).apply()
        Toast.makeText(this, "✅ 已將「$name」加入限免/降價追蹤！", Toast.LENGTH_SHORT).show()
    }

    private fun removeFromWatchlist(trackId: Long) {
        val prefs = getSharedPreferences("watchlist", Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString("apps_json", "[]"))
        val newArr = JSONArray()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            if (obj.getLong("trackId") != trackId) {
                newArr.put(obj)
            }
        }
        prefs.edit().putString("apps_json", newArr.toString()).apply()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "appstore_deals_channel",
                "App Store 限免與特價通知",
                NotificationManager.IMPORTANCE_HIGH
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun sendDealNotification(title: String, message: String, url: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = NotificationCompat.Builder(this, "appstore_deals_channel")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
        nm.notify(System.currentTimeMillis().toInt(), builder.build())
    }
}
