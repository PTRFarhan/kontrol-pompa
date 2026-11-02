import http.server, socketserver, time, sys
class H(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path.startswith('/redir'):
            self.send_response(307); self.send_header('Location','/data/F.json'); self.end_headers(); return
        self.send_response(200); self.send_header('Content-Type','text/event-stream'); self.send_header('Cache-Control','no-cache'); self.end_headers()
        w=lambda s:(self.wfile.write(s.encode()), self.wfile.flush())
        w('event: put\ndata: {"path":"/","data":{"toren":{"d7":{"jarak":55,"kosong":100,"penuh":20,"kapasitas":1000}},"pompa":{"status":{"state":"OFF","sumber":"-"}},"perangkat":{"ts":1}}}\n\n')
        time.sleep(0.3); w('event: keep-alive\ndata: null\n\n')
        time.sleep(0.3); w('event: patch\ndata: {"path":"/toren/d7","data":{"jarak":92}}\n\n')
        time.sleep(0.3); w('event: put\ndata: {"path":"/pompa/status","data":{"state":"ON","sumber":"MANUAL","oleh":""}}\n\n')
        time.sleep(0.3)
    def log_message(self,*a): pass
socketserver.TCPServer.allow_reuse_address=True
socketserver.ThreadingTCPServer(('127.0.0.1',int(sys.argv[1])),H).serve_forever()
