import json
import time
import os
import uuid
import socket
import ssl
from datetime import datetime
import paho.mqtt.client as mqtt
from zeroconf import IPVersion, ServiceInfo, Zeroconf

def get_local_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.settimeout(0)
    try:
        s.connect(('10.254.254.254', 1))
        ip = s.getsockname()[0]
    except Exception:
        ip = '127.0.0.1'
    finally:
        s.close()
    return ip

BROKER_IP = get_local_ip()
BROKER_PORT = 8883
COMMAND_TOPIC = "synaptimesh/commands"
ACK_TOPIC = "synaptimesh/ack"
TIMEOUT_SEC = 25.0

BASE_DIR = os.path.dirname(os.path.abspath(__file__))

running = True
sent_messages = {}

def on_message(client, userdata, msg):
    if msg.topic == ACK_TOPIC:
        try:
            ack_data = json.loads(msg.payload.decode())
            mid = ack_data.get("command_id", ack_data.get("message_id", "unknown"))
            status = ack_data.get("status", "UNKNOWN")
            
            if mid in sent_messages:
                print(f"\n[{datetime.now().strftime('%H:%M:%S')}] [ACK] Command {mid} completed with status: {status}")
                if "reason" in ack_data:
                    print(f"       Reason: {ack_data['reason']}")
                sent_messages.pop(mid, None)
        except:
            pass

client = mqtt.Client(client_id=f"Demo_{uuid.uuid4().hex[:8]}")
client.username_pw_set("synaptimesh", "SynaptiMesh2026!")
client.tls_set(ca_certs=os.path.join(BASE_DIR, "certs", "ca.crt"), 
               certfile=os.path.join(BASE_DIR, "certs", "python_client.crt"), 
               keyfile=os.path.join(BASE_DIR, "certs", "python_client.key"), 
               tls_version=ssl.PROTOCOL_TLSv1_2)
client.on_message = on_message
client.connect(BROKER_IP, BROKER_PORT, 60)
client.subscribe(ACK_TOPIC, qos=1)
client.loop_start()

zeroconf = Zeroconf()
service_info = ServiceInfo(
    "_mqtt._tcp.local.",
    "SynaptiMesh Broker._mqtt._tcp.local.",
    addresses=[socket.inet_aton(BROKER_IP)],
    port=BROKER_PORT,
    properties={"version": "1.0"},
    server="synaptimesh.local.",
)
try:
    zeroconf.register_service(service_info, allow_name_change=True)
except Exception:
    pass

demo_sequence = [
    {"command": "RIGHT_LAUNCH_JIOSAAVN", "delay": 2},
    {"command": "RIGHT_SEARCH_PLAYLIST", "delay": 2, "parameters": {"playlist": "A.R. Rahman Hits"}},
    {"command": "RIGHT_PLAY_PAUSE", "delay": 5},
    {"command": "RIGHT_NEXTTRACK", "delay": 5},
    {"command": "RIGHT_PREVTRACK", "delay": 5},
    {"command": "RIGHT_VOLUMEUP", "delay": 2},
    {"command": "RIGHT_VOLUMEDOWN", "delay": 2},
    {"command": "RIGHT_RETURN_TO_HOME", "delay": 2}
]

sequence_counter = 5000

print("=" * 60)
print("SYNAPTIMESH UI DEMO SCRIPT")
print("=" * 60)

for step in demo_sequence:
    command = step["command"]
    command_id = f"CMD-DEMO-{sequence_counter}"
    
    payload = {
        "protocol_version": 2,
        "sequence_number": sequence_counter,
        "command_id": command_id,
        "command": command,
        "confidence": 0.99,
        "sent_time_ms": int(time.time() * 1000)
    }
    if "parameters" in step:
        payload["parameters"] = step["parameters"]

    print(f"\n>> Sending: {command}")
    sent_messages[command_id] = {"command": command, "sent_time": time.time()}
    client.publish(COMMAND_TOPIC, json.dumps(payload), qos=1)
    
    # Wait for ACK
    while command_id in sent_messages:
        if time.time() - sent_messages[command_id]["sent_time"] > TIMEOUT_SEC:
            print(f"[!] TIMEOUT waiting for ACK for {command}")
            sent_messages.pop(command_id, None)
            break
        time.sleep(0.1)
        
    time.sleep(step["delay"])
    sequence_counter += 1

print("\n[SYSTEM] Demo Sequence Complete.")
zeroconf.unregister_service(service_info)
zeroconf.close()
client.loop_stop()
client.disconnect()
