import socket
import threading
import time

def handle_client(client_socket, remote_host, remote_port):
    try:
        remote_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        remote_socket.connect((remote_host, remote_port))
        
        def forward(src, dst):
            try:
                while True:
                    data = src.recv(4096)
                    if not data: break
                    dst.sendall(data)
            except: pass
            finally:
                try: src.close()
                except: pass
                try: dst.close()
                except: pass
                
        threading.Thread(target=forward, args=(client_socket, remote_socket)).start()
        threading.Thread(target=forward, args=(remote_socket, client_socket)).start()
    except Exception as e:
        print("Proxy error:", e)
        client_socket.close()

def start_proxy(local_host, local_port, remote_host, remote_port):
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        server.bind((local_host, local_port))
    except Exception as e:
        print("Failed to bind:", e)
        return
    server.listen(100)
    print(f"Proxying {local_host}:{local_port} to {remote_host}:{remote_port}")
    
    while True:
        try:
            client_socket, addr = server.accept()
            print("Accepted connection from", addr)
            threading.Thread(target=handle_client, args=(client_socket, remote_host, remote_port)).start()
        except Exception as e:
            print("Accept error:", e)
            time.sleep(1)

if __name__ == '__main__':
    start_proxy('172.18.9.82', 1883, '127.0.0.1', 1883)
