#!/usr/bin/env sh
set -eu

container_name="accountshield-frontend-invalid"
cleanup() {
  docker rm --force "$container_name" >/dev/null 2>&1 || true
}
trap cleanup EXIT

docker run --detach --name "$container_name" \
  --env NEXT_PUBLIC_APP_ENV=production \
  --env ACCOUNTSHIELD_DATA_SOURCE=live \
  --env ACCOUNTSHIELD_API_URL=https://api.example.invalid \
  accountshield-frontend:ci >/dev/null

invalid_state="running"
attempt=1
while [ "$attempt" -le 20 ]; do
  invalid_state="$(docker inspect --format='{{.State.Status}}' "$container_name")"
  if [ "$invalid_state" = "exited" ]; then
    break
  fi
  sleep 1
  attempt=$((attempt + 1))
done

if [ "$invalid_state" != "exited" ]; then
  docker logs "$container_name"
  echo "Expected the mismatched image to terminate." >&2
  exit 1
fi

exit_code="$(docker inspect --format='{{.State.ExitCode}}' "$container_name")"
if [ "$exit_code" = "0" ]; then
  docker logs "$container_name"
  echo "Expected a non-zero exit code for mismatched build/runtime modes." >&2
  exit 1
fi

docker logs "$container_name" 2>&1 \
  | grep --fixed-strings "does not match image build environment"
