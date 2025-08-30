import requests
import json

# server URL
# run: uvicorn main:app --reload --port 8000
# to run the test: python3 test/test_backend.py
url = "http://127.0.0.1:8000/verify"

with open("test/demo.jpg.sig.json") as f:
    data = json.load(f)

print(type(data))
print(data.keys())

payload = [
    ("payload_canonical", data["payload_canonical"]),
    ("sig_b64", data["sig_b64"]),
] + [("x5c_der_b64", cert) for cert in data["x5c_der_b64"]]

media_path = "test/demo.jpg"

files = {
    "media": open(media_path, "rb")
}

response = requests.post(url, data=payload, files=files)
print(response.status_code)
try:
    data = response.json()
    print("Parsed JSON:", data)
except ValueError:  # includes JSONDecodeError
    print("Not valid JSON, raw response was:")
    print(response.text)
