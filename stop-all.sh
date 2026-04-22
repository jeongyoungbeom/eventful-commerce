#!/bin/bash

echo "🛑 Stopping Eventful Commerce - Full Stack"
echo "=========================================="
echo ""

echo "📦 Stopping all containers..."
docker-compose down

echo ""
echo "✅ All services stopped!"
echo ""
echo "💡 To remove volumes (data will be lost):"
echo "   docker-compose down -v"
echo ""
