import socket, sys, hashlib, threading, time
port=int(sys.argv[1]); password=sys.argv[2]; mode=sys.argv[3]; out=sys.argv[4]
u=socket.socket(socket.AF_INET,socket.SOCK_DGRAM); u.bind(("127.0.0.1",port)); u.settimeout(30)
data,addr=u.recvfrom(256); parts=data.decode().split()
cmd,lport,size,md5=int(parts[0]),int(parts[1]),int(parts[2]),parts[3]
passmd5=hashlib.md5(password.encode()).hexdigest()
nonce=hashlib.md5(str(time.time()).encode()).hexdigest()
u.sendto(("AUTH "+nonce).encode(),addr)
data,addr=u.recvfrom(256); p=data.decode().split()
exp=hashlib.md5((passmd5+":"+nonce+":"+p[1]).encode()).hexdigest()
if p[0]!="200" or p[2]!=exp:
    u.sendto(b"Authentication Failed",addr); print("AUTH FAIL"); sys.exit(0)
u.sendto(b"OK",addr)
c=socket.create_connection(("127.0.0.1",lport),timeout=20)
got=b""
while len(got)<size:
    chunk=c.recv(1460)
    if not chunk: break
    got+=chunk
    c.sendall(str(len(chunk)).encode())
ok=hashlib.md5(got).hexdigest()==md5 and len(got)==size
print("MD5 OK" if ok else "MD5 BAD", len(got))
if mode=="silent": time.sleep(5)
else: c.sendall(b"OK" if ok else b"Error")
time.sleep(0.5); c.close()
