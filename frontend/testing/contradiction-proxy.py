import http.client
import json
import os
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit


upstream = urlsplit(os.environ.get("EMULATOR_URL", "http://emulator:8090"))
state = {"id": None, "observed_original": None, "delivered": None}
state_lock = threading.Lock()


class ProxyHandler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        pass

    def respond(self, status, body, content_type="application/json"):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def dispatch(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
        if self.path == "/__test/contradiction":
            with state_lock:
                if self.command == "PUT":
                    state.update(id=json.loads(body)["id"], observed_original=None, delivered=None)
                elif self.command == "DELETE":
                    state.update(id=None, observed_original=None, delivered=None)
                self.respond(200, json.dumps(state).encode())
            return
        connection = http.client.HTTPConnection(upstream.hostname, upstream.port or 80, timeout=15)
        headers = {
            key: value for key, value in self.headers.items()
            if key.lower() not in {"host", "connection", "content-length"}
        }
        try:
            connection.request(self.command, self.path, body=body, headers=headers)
            response = connection.getresponse()
            content = response.read()
            with state_lock:
                if (
                    self.command == "GET"
                    and state["id"] is not None
                    and self.path.endswith("/operations/" + state["id"])
                    and response.status == 200
                ):
                    original = json.loads(content)
                    if original.get("id") == state["id"] and original.get("status") == "success":
                        delivered = dict(original, status="fail", data="")
                        state.update(observed_original=original, delivered=delivered)
                        content = json.dumps(delivered).encode()
            self.respond(response.status, content, response.getheader("Content-Type", "application/json"))
        except (OSError, http.client.HTTPException):
            self.respond(502, b'{"error":"TestProxyUpstreamUnavailable"}')
        finally:
            connection.close()

    do_GET = dispatch
    do_POST = dispatch
    do_PUT = dispatch
    do_DELETE = dispatch


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 8091), ProxyHandler).serve_forever()
