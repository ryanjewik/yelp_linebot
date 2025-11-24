import requests

url = "https://api.yelp.com/ai/chat/v2"

headers = {
    "accept": "application/json",
    "content-type": "application/json",
    "authorization": "Bearer pugj912iyPIjjzHu2W5WEUQWmF4L4BtFNASB_gSNanSYejh_9qwadAAxmIWEle4Xj8hTyjyBEc5j6kdpb-oywZ_2tquFZ9Ibrh5udkYrqI3VJKvVBI47or-rEdsiaXYx"
}
req = {
  "query": "What's a good vegan pizza place near me?",
  "user_context": {
    "locale": "en_US",
    "latitude": 40.7128,
    "longitude": -74.0060
  }
}
response = requests.post(url, headers=headers, json=req)

print(response.text)