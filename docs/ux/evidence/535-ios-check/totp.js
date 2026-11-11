// The code changes every 30 s, so it is fetched at the moment it is typed.
output.code = http.get('http://127.0.0.1:8097/').body
