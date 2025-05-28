#!/bin/bash

echo "🔍 Исследование правильного формата clusters-config.json..."
echo ""

# 1. Проверка существующего файла
echo "📋 Проверка файла cluster_config.json в контейнере:"
docker-compose exec ytsaurus-ui sh -c '
    cd /opt/app/clusters-config.json/
    if [ -f cluster_config.json ]; then
        echo "Найден файл примера:"
        cat cluster_config.json
    else
        echo "Файл примера не найден"
        echo "Содержимое директории:"
        ls -la
    fi
'

# 2. Проверка исходного кода для понимания формата
echo ""
echo "🔍 Поиск подсказок в коде приложения:"
docker-compose exec ytsaurus-ui sh -c '
    echo "Проверка config.realcluster.js:"
    grep -A5 -B5 "clusters-config" /opt/app/dist/server/config.realcluster.js 2>/dev/null || echo "Файл не найден"
    echo ""
    echo "Проверка на наличие примеров:"
    find /opt/app -name "*.example*" -o -name "*example*.json" 2>/dev/null | head -5
'

# 3. Тестирование разных форматов
echo ""
echo "🧪 Тестирование разных форматов конфигурации..."

formats=(
'{
   "clusters":[
   {
     "id": "local",
     "name": "Local YTsaurus",
     "proxy": "http://host.docker.internal:80",
     "externalProxy": "http://localhost:80",
     "theme": "grapefruit"
   }
 ]
 }'
)

for i in "${!formats[@]}"; do
    echo ""
    echo "Тест формата #$((i+1)):"
    echo "${formats[$i]}" > clusters-config.json

    docker-compose restart ytsaurus-ui > /dev/null 2>&1
    sleep 5

    # Проверка результата
    HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8080)
    if [ "$HTTP_CODE" = "200" ] || [ "$HTTP_CODE" = "302" ]; then
        echo "✅ Формат #$((i+1)) работает!"
        echo "Содержимое:"
        cat clusters-config.json
        break
    else
        echo "❌ Формат #$((i+1)) не работает (HTTP $HTTP_CODE)"
    fi
done

# 4. Финальная рекомендация
echo ""
echo "💡 Рекомендации:"
echo "1. Используйте рабочий docker-compose-working.yml"
echo "2. Или скопируйте рабочий формат выше в clusters-config.json"