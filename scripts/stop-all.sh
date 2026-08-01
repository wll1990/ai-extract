#!/bin/bash
cd /opt/mindforge/logs

for f in *.pid; do
    [ -f "$f" ] && kill $(cat "$f") 2>/dev/null && echo "stopped $f" || true
done

sleep 1
echo "all services stopped"
