#!/usr/bin/env zsh
echo "Killing java -jar $1"

# Find all PIDs of java -jar processes (excluding grep)
pids=($(ps aux | grep "[j]ava -jar .*commit-diff-context-mcp.*" | tr -s ' ' | cut -d ' ' -f 2))

if [[ ${#pids[@]} -eq 0 ]]; then
  echo "No java -jar processes found."
else
  echo "Killing PIDs: $pids"
  for pid in $pids; do
    kill "$pid" || true
    echo "Killed $pid"
  done
fi
