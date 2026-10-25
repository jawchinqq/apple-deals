import json
import os
import urllib.request
import urllib.parse
import xml.etree.ElementTree as ET

def check_watchlist(config):
    country = config.get("country", "tw")
    apps = config.get("watchlist_apps", [])
    if not apps:
        return []

    ids = ",".join(str(a["trackId"]) for a in apps)
    url = f"https://itunes.apple.com/lookup?id={ids}&country={country}"
    alerts = []

    try:
        req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(req, timeout=15) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            results = {item["trackId"]: item for item in data.get("results", [])}

            for app in apps:
                tid = app["trackId"]
                target = app.get("target_price", 0)
                info = results.get(tid)
                if not info:
                    continue

                price = info.get("price", 99999)
                formatted = info.get("formattedPrice", f"NT$ {price}")
                track_name = info.get("trackName", app["name"])
                store_url = info.get("trackViewUrl", "")

                if price == 0:
                    alerts.append(f"🎁 【限時免費 $0！】{track_name}\n目前價格：{formatted}\n立刻領取：{store_url}")
                elif price <= target:
                    alerts.append(f"📉 【降價達標！】{track_name}\n目前特價：{formatted} (低於設定目標 {target})\n前往查看：{store_url}")
                else:
                    print(f"[正常] {track_name}: 目前售價 {formatted} (目標 <= {target})")
    except Exception as e:
        print(f"查詢 iTunes API 發生錯誤: {e}")

    return alerts

def check_global_free_ios_deals():
    url = "https://www.reddit.com/r/apphookup/new/.rss"
    deals = []
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0) AppleStoreRadar/1.0"})
        with urllib.request.urlopen(req, timeout=15) as resp:
            xml_bytes = resp.read()
            root = ET.fromstring(xml_bytes)
            ns = {"atom": "http://www.w3.org/2005/Atom"}
            for entry in root.findall("atom:entry", ns)[:8]:
                title_el = entry.find("atom:title", ns)
                link_el = entry.find("atom:link", ns)
                title = title_el.text if title_el is not None else ""
                link = link_el.attrib.get("href", "") if link_el is not None else ""
                if any(k in title.lower() for k in ["[ios]", "[mac]", "[apple]", "ipad"]) and ("$0" in title.lower() or "free" in title.lower()):
                    deals.append(f"🔥 {title}\n🔗 {link}")
    except Exception as e:
        print(f"讀取全球限免 RSS 略過: {e}")
    return deals[:3]

def send_notifications(messages):
    if not messages:
        print("本次巡邏無新的限免或達標特價。")
        return

    full_text = "🍎 【Apple Store 限免與特價快報】\n\n" + "\n\n".join(messages)
    print(full_text)

    # 1. Discord Webhook 推播
    discord_webhook = os.environ.get("DISCORD_WEBHOOK_URL", "").strip()
    if discord_webhook:
        try:
            payload = json.dumps({"content": full_text}).encode("utf-8")
            req = urllib.request.Request(discord_webhook, data=payload, headers={"Content-Type": "application/json", "User-Agent": "Mozilla/5.0"})
            urllib.request.urlopen(req, timeout=10)
            print("✅ 已推播至 Discord！")
        except Exception as e:
            print(f"Discord 推播失敗: {e}")

    # 2. Telegram Bot 推播
    tg_token = os.environ.get("TELEGRAM_BOT_TOKEN", "").strip()
    tg_chat_id = os.environ.get("TELEGRAM_CHAT_ID", "").strip()
    if tg_token and tg_chat_id:
        try:
            tg_url = f"https://api.telegram.org/bot{tg_token}/sendMessage"
            payload = urllib.parse.urlencode({"chat_id": tg_chat_id, "text": full_text}).encode("utf-8")
            urllib.request.urlopen(tg_url, data=payload, timeout=10)
            print("✅ 已推播至 Telegram！")
        except Exception as e:
            print(f"Telegram 推播失敗: {e}")

    # 3. LINE Messaging API 推播
    line_token = os.environ.get("LINE_CHANNEL_ACCESS_TOKEN", "").strip()
    line_user_id = os.environ.get("LINE_USER_ID", "").strip()
    if line_token and line_user_id:
        try:
            line_url = "https://api.line.me/v2/bot/message/push"
            body = {
                "to": line_user_id,
                "messages": [{"type": "text", "text": full_text}]
            }
            req = urllib.request.Request(
                line_url,
                data=json.dumps(body).encode("utf-8"),
                headers={
                    "Content-Type": "application/json",
                    "Authorization": f"Bearer {line_token}"
                }
            )
            urllib.request.urlopen(req, timeout=10)
            print("✅ 已推播至 LINE！")
        except Exception as e:
            print(f"LINE 推播失敗: {e}")

if __name__ == "__main__":
    script_dir = os.path.dirname(os.path.abspath(__file__))
    config_path = os.path.join(script_dir, "watchlist.json")
    with open(config_path, "r", encoding="utf-8") as f:
        config = json.load(f)

    alerts = check_watchlist(config)
    if config.get("enable_global_free_radar", True):
        alerts.extend(check_global_free_ios_deals())

    send_notifications(alerts)
