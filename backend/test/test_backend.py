import requests
import json

# server URL
# run: uvicorn main:app --reload --port 8000
# to run the test: python3 test/test_backend.py
url = "http://127.0.0.1:8000/verify"

with open("test/demo.jpg.sig.json") as f:
    data = json.load(f)

media_path = "test/demo.jpg"

files = {
    "media": open(media_path, "rb")
}

payload = {
    "payload_canonical": data["payload_canonical"],
    "sig_b64": data["sig_b64"],
    "x5c_der_b64": json.dumps(data["x5c_der_b64"])
}

response = requests.post(url, data=payload, files=files)
print(response.status_code)
print(response.json())
