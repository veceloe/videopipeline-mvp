import os, subprocess, time, json, glob
from kafka import KafkaProducer

VIDEO = "/media/sample.mp4"
SEG_DIR = "/media/segments"
TOPIC = "video_stream"
os.makedirs(SEG_DIR, exist_ok=True)

# 1. запустить ffmpeg-сегментацию в фоне
seg_cmd = [
    "ffmpeg", "-loglevel", "quiet", "-re", "-stream_loop", "-1",
    "-i", VIDEO, "-c", "copy",
    "-f", "segment", "-segment_time", "5", "-reset_timestamps", "1",
    f"{SEG_DIR}/segment%06d.mp4"
]
subprocess.Popen(seg_cmd)

# 2. Kafka-продюсер (байтовый value)
producer = KafkaProducer(
    bootstrap_servers="kafka:9092",
    key_serializer=lambda k: k.encode(),
    value_serializer=lambda v: v  # raw bytes
)

sent = set()
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