import os, subprocess, time, json, glob
from kafka import KafkaProducer
from kafka.errors import NoBrokersAvailable

VIDEO = "/media/sample.mp4"
SEG_DIR = "/media/segments"
TOPIC = "video_stream"
KAFKA_BOOTSTRAP_SERVERS = "kafka:9092"
MAX_RETRIES = 10
RETRY_DELAY_SECONDS = 5

os.makedirs(SEG_DIR, exist_ok=True)

seg_cmd = [
    "ffmpeg", "-loglevel", "quiet", "-re", "-stream_loop", "-1",
    "-i", VIDEO, "-c", "copy",
    "-f", "segment", "-segment_time", "5", "-reset_timestamps", "1",
    f"{SEG_DIR}/segment%06d.mp4"
]
subprocess.Popen(seg_cmd)

producer = None
for attempt in range(MAX_RETRIES):
    try:
        producer = KafkaProducer(
            bootstrap_servers=KAFKA_BOOTSTRAP_SERVERS,
            key_serializer=lambda k: k.encode(),
            value_serializer=lambda v: v,
            reconnect_backoff_ms=1000,
            retry_backoff_ms=1000,
        )
        print(f"Kafka producer connected to {KAFKA_BOOTSTRAP_SERVERS}")
        break
    except NoBrokersAvailable:
        print(f"Attempt {attempt + 1}/{MAX_RETRIES}: Kafka not available yet, retrying in {RETRY_DELAY_SECONDS}s...")
        time.sleep(RETRY_DELAY_SECONDS)

if producer is None:
    print(f"Failed to connect to Kafka after {MAX_RETRIES} attempts. Exiting.", flush=True)
    exit(1)

sent = set()
try:
    while True:
        for path in sorted(glob.glob(f"{SEG_DIR}/segment*.mp4")):
            if path in sent:
                continue
            with open(path, "rb") as f:
                data = f.read()
            name = os.path.basename(path)
            producer.send(TOPIC, key=name, value=data)
            print(f"sent {name} ({len(data)} bytes)")
            sent.add(path)
        producer.flush()
        time.sleep(1)
except Exception as e:
    print(f"Error in producer loop: {e}")
finally:
    if producer:
        producer.close()
        print("Kafka producer closed.")