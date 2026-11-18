import io
from email.message import Message
import unittest
import urllib.error
from unittest.mock import MagicMock, patch

from backend.load.profile import Client


class ClientTest(unittest.TestCase):
    def setUp(self):
        self.client = Client.__new__(Client)
        self.client.base_url = "http://localhost:8080"
        self.client.csrf = None
        self.client.opener = MagicMock()
        self.response = MagicMock()
        self.response.__enter__.return_value = self.response
        self.response.headers = {"X-CSRF-TOKEN": "token"}
        self.response.status = 200
        self.response.read.return_value = b'{"id":1}'
        self.client.opener.open.return_value = self.response

    def test_reads_success_and_csrf(self):
        self.assertEqual(self.client.request("GET", "/api/me"), (200, {"id": 1}))
        self.assertEqual(self.client.csrf, "token")

    def test_invalid_json_is_an_error_even_with_http_200(self):
        self.response.read.return_value = b"{"
        status, body = self.client.request("GET", "/api/me")
        self.assertEqual(status, 0)
        self.assertEqual(body["code"], "InvalidResponse:HTTP200")

    def test_body_timeout_is_counted_without_retry(self):
        self.response.read.side_effect = TimeoutError("body timeout")
        status, body = self.client.request("GET", "/api/me")
        self.assertEqual(status, 0)
        self.assertIn("TimeoutError", body["code"])
        self.client.opener.open.assert_called_once()

    def test_connect_failure_keeps_diagnostics(self):
        self.client.opener.open.side_effect = urllib.error.URLError("connect failure")
        status, body = self.client.request("GET", "/api/me")
        self.assertEqual(status, 0)
        self.assertIn("connect failure", body["code"])

    def test_preserves_http_error_status_and_body(self):
        self.client.opener.open.side_effect = urllib.error.HTTPError(
            "http://localhost:8080/api/tasks",
            422,
            "MissingFields",
            {},
            io.BytesIO(b'{"code":"MissingFields"}'),
        )
        self.assertEqual(
            self.client.request("GET", "/api/tasks"),
            (422, {"code": "MissingFields"}),
        )


class PersistentSessionTest(unittest.TestCase):
    def response(self, status, body, cookie=None):
        response = MagicMock()
        response.__enter__.return_value = response
        response.status = status
        response.headers = Message()
        response.headers["X-CSRF-TOKEN"] = "token"
        if cookie:
            response.headers["Set-Cookie"] = cookie
        response.info.return_value = response.headers
        response.read.return_value = body
        return response

    @patch("backend.load.profile.http.client.HTTPConnection")
    def test_reuses_connection_and_rotates_session_cookie(self, connection_type):
        connection = connection_type.return_value
        connection.getresponse.side_effect = [
            self.response(401, b'{"code":"Unauthorized"}', "SESSION=initial; Path=/"),
            self.response(200, b'{"id":1}', "SESSION=rotated; Path=/"),
            self.response(200, b'{"id":1}'),
        ]
        client = Client("http://localhost:8080", "user", "password")
        self.assertEqual(client.request("GET", "/api/me"), (200, {"id": 1}))
        connection_type.assert_called_once_with("localhost", 8080, timeout=10)
        self.assertEqual(connection.request.call_count, 3)
        headers = connection.request.call_args.kwargs["headers"]
        self.assertEqual(headers["Cookie"], "SESSION=rotated")
        self.assertNotEqual(headers.get("Connection"), "close")

    @patch("backend.load.profile.http.client.HTTPConnection")
    def test_connection_failure_is_not_retried(self, connection_type):
        from backend.load.profile import PersistentSession
        import urllib.request

        connection = connection_type.return_value
        connection.request.side_effect = ConnectionResetError("reset")
        session = PersistentSession("http://localhost:8080")
        with self.assertRaises(ConnectionResetError):
            session.open(urllib.request.Request("http://localhost:8080/api/me"), 10)
        connection.request.assert_called_once()
        connection.close.assert_called_once()


if __name__ == "__main__":
    unittest.main()
