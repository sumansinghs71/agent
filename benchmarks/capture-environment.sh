#!/usr/bin/env bash
# Records the machine a benchmark ran on. A number without its environment is not comparable to
# any other number, and is easily mistaken for a capacity claim.
set -euo pipefail
cat <<JSON
{
  "date": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "commit": "$(git rev-parse HEAD)",
  "branch": "$(git rev-parse --abbrev-ref HEAD)",
  "machine": "$(sysctl -n hw.model 2>/dev/null || uname -m)",
  "cpu": "$(sysctl -n machdep.cpu.brand_string 2>/dev/null || grep -m1 'model name' /proc/cpuinfo | cut -d: -f2- | xargs)",
  "cores": "$(sysctl -n hw.ncpu 2>/dev/null || nproc)",
  "ram_bytes": "$(sysctl -n hw.memsize 2>/dev/null || (grep MemTotal /proc/meminfo | awk '{print $2*1024}'))",
  "os": "$(uname -srm)",
  "jdk": "$(java -version 2>&1 | head -1 | tr -d '\"')",
  "docker": "$(docker --version 2>/dev/null || echo 'not available')",
  "postgres_image": "postgres:16-alpine",
  "note": "Local reproducible benchmark results on a single developer machine. NOT a production capacity measurement."
}
JSON
