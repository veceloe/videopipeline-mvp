FROM python:3.10-slim

RUN apt-get update && \
    apt-get install -y --no-install-recommends ffmpeg && \
    pip install kafka-python watchdog && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY producer.py .

CMD ["python", "producer.py"] 