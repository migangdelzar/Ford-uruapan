ford_compose() {
  if docker compose version >/dev/null 2>&1; then
    docker compose "$@"
  elif command -v docker-compose >/dev/null 2>&1; then
    docker-compose "$@"
  else
    echo "Docker Compose is unavailable; install the Docker Compose plugin or docker-compose." >&2
    return 127
  fi
}
