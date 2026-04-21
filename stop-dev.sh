#!/bin/bash

echo "🛑 Stopping development infrastructure..."
echo ""

# 컨테이너 중지
docker-compose -f docker-compose-dev.yml down

echo ""
echo "✅ All services stopped!"
echo ""
echo "💡 To remove all data: docker-compose -f docker-compose-dev.yml down -v"
echo "🚀 To restart:         ./start-dev.sh"
echo ""
