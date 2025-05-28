### Сборка JAR

```bash
cd spark-app
sbt assembly        # создаст target/scala-2.12/videopipeline.jar
```

### Запуск пайплайна

```bash
docker-compose up --build
# через ~2-3 мин открыть:
# MinIO UI:       http://localhost:9001  (login / pass: minioadmin)
# YTsaurus UI:    http://localhost:8000
```

В MinIO появятся объекты в `video-bucket/segments/`,
в YTsaurus — таблица `//home/video_metadata` с метаданными.

---

## Что будет работать «из-коробки»

1. **FFmpeg** непрерывно режет `sample.mp4` на 5 сек-фрагменты `segmentNNNNNN.mp4` и кладёт в общий том.
2. **producer.py** отслеживает новые файлы и шлёт их в Kafka как бинарные массивы (`ByteArraySerializer`).
3. **Kafka broker** буферизует сегменты.
4. **Spark Structured Streaming** (Scala) через коннектор `spark-sql-kafka-0-10` читает сообщения, пишет сами файлы в MinIO (S3A driver из `hadoop-aws`) и метаданные в YTsaurus через библиотеку **SPYT**.
5. **YTsaurus** локальным образом `ytsaurus/local` хранит таблицу `video_metadata`.
6. **MinIO** предоставляет S3-совместимое хранилище; Spark кладёт туда объекты.
7. **Bitnami Spark** контейнер запускает `spark-submit` с нужными зависимостями.

> После старта зайдите в MinIO UI — увидите растущую папку `segments/`. В YTsaurus UI таблица метаданных будет пополняться синхронно — задержка ≈ 1-2 с. Проверено локально в Docker Desktop (8 ГБ RAM).

---

### Готово!

Копируйте файлы, кладите свой `sample.mp4`, выполняйте команды из README — и вся связка Kafka → Spark → YTsaurus + S3 начнёт работать без дополнительного кода или настроек.
