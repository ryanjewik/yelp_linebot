#!/usr/bin/env bash

# Ensure we're running under bash. The restore script uses bash-specific features
# (arrays, shopt nullglob, etc.). If the container's default /bin/sh is not bash
# (for example an alpine/busybox image), fail early with a helpful message.
if [ -z "${BASH_VERSION:-}" ]; then
  cat >&2 <<'MSG'
This restore script requires bash but the current shell is not bash.
Please use a restore image that includes bash (for example the Debian-based
Postgres image) or set the environment variable LINEBOT_RESTORE_IMAGE to a
compatible image (e.g. 'postgres:18' not 'postgres:18-alpine').

Example: in your docker-compose set
  LINEBOT_RESTORE_IMAGE=postgres:18
MSG
  exit 2
fi

set -euo pipefail

DB_HOST=${LINEBOT_DB_HOST:-linebot-db}
DB_PORT=${LINEBOT_DB_PORT:-5432}
DB_USER=${DB_USERNAME:-postgres}
DB_NAME=${DB_NAME:-linebot}
DB_PASS=${DB_PASSWORD:-}
# If FORCE_RESTORE is unset, default to 1 so the restore runs on a fresh
# developer machine the first time. If the variable is explicitly provided
# (in the environment or via docker-compose), use that value.
FORCE=${FORCE_RESTORE:-1}

export PGPASSWORD="$DB_PASS"

echo "Waiting for Postgres at $DB_HOST:$DB_PORT..."
for i in $(seq 1 60); do
  if pg_isready -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" >/dev/null 2>&1; then
    echo "Postgres is available"
    break
  fi
  echo "  waiting ($i) ..."
  sleep 1
done

# Debug: show resolved connection targets so logs make it clear which DB the script uses.
echo "Using connection: host=$DB_HOST port=$DB_PORT user=$DB_USER dbname=$DB_NAME"

## Use a marker table to decide whether a restore already ran. This is more reliable
## than counting tables because some schemas may be present but incomplete.
MARKER_CHECK=$(psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -Atc "SELECT to_regclass('public._linebot_restore_meta');" 2>/dev/null || echo "")

# If a restore marker exists we should normally skip the restore. However, if
# the user explicitly set FORCE_RESTORE in the environment to "1" we allow
# forcing a restore to override the marker. The test below distinguishes an
# unset environment variable from one explicitly set by checking the parameter
# expansion form.
if [ -n "$MARKER_CHECK" ]; then
  if [ -z "${FORCE_RESTORE+x}" ]; then
    echo "Restore marker found (public._linebot_restore_meta); skipping restore (FORCE_RESTORE unset)."
    exit 0
  else
    if [ "${FORCE_RESTORE}" != "1" ]; then
      echo "Restore marker found (public._linebot_restore_meta); skipping restore (FORCE_RESTORE != 1)."
      exit 0
    else
      echo "Restore marker found but FORCE_RESTORE=1 explicitly set; continuing (force)."
    fi
  fi
fi

# If FORCE is set (or defaulted), attempt a clean drop+create of the target database so the
# restore runs against an empty DB. This requires a DB user with CREATE/DROP
# privileges (the default Postgres superuser created by the image does).
if [ "${FORCE:-0}" = "1" ]; then
  echo "FORCE_RESTORE=1: dropping and recreating database '$DB_NAME'"
  tries=0
  max_tries=12
  wait_s=2
  while true; do
    # Try to terminate any connections to the target DB (may be none)
    set +e
    terminate_out=$(psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d postgres -v ON_ERROR_STOP=1 -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '$DB_NAME' AND pid <> pg_backend_pid();" 2>&1)
    term_rc=$?

    # Drop and recreate using dropdb/createdb so commands run outside a transaction block
    drop_out=$(dropdb -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" --if-exists "$DB_NAME" 2>&1)
    drop_rc=$?
    create_out=$(createdb -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" "$DB_NAME" 2>&1)
    create_rc=$?
    set -e

    if [ $drop_rc -eq 0 ] && [ $create_rc -eq 0 ]; then
      echo "Successfully recreated database '$DB_NAME'"
      break
    fi

    tries=$((tries+1))
    echo "Failed to recreate database (attempt $tries/$max_tries). drop_rc=$drop_rc create_rc=$create_rc; terminate_out: $(echo "$terminate_out" | sed -n '1,2p')" >&2
    if [ $tries -ge $max_tries ]; then
      echo "Giving up recreating database after $max_tries attempts" >&2
      break
    fi
    sleep $wait_s
  done
fi

## Use a marker table to decide whether a restore already ran. This is more reliable
## than counting tables because some schemas may be present but incomplete.
MARKER_CHECK=$(psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -Atc "SELECT to_regclass('public._linebot_restore_meta');" 2>/dev/null || echo "")
# The marker check has already been performed above; no-op here.

shopt -s nullglob
# Prefer custom dumps (*.dump) for pg_restore
DUMPS=(/db_init/*.dump /db_init/*.pgdump)
SQLS=(/db_init/*.sql)

RESTORED_FILES=()
if [ ${#DUMPS[@]} -gt 0 ]; then
  for f in "${DUMPS[@]}"; do
    echo "Restoring dump file: $f"
    # Detect Postgres custom-format dump (has magic header 'PGDMP') and use pg_restore when present.
    if head -c 5 "$f" 2>/dev/null | grep -q "PGDMP"; then
      echo "Detected custom-format dump; using pg_restore"
      # Run pg_restore but don't let set -e abort the script on non-zero exit
      set +e
      pg_restore -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -v "$f"
      rc=$?
      set -e
      if [ "$rc" -ne 0 ]; then
        echo "pg_restore returned non-zero exit code $rc (continuing to marker write)." >&2
      fi
    else
      echo "Not a custom-format dump; applying with psql"
      # Run psql but don't let set -e abort the script on non-zero exit
      set +e
      psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -f "$f"
      rc=$?
      set -e
      if [ "$rc" -ne 0 ]; then
        echo "psql returned non-zero exit code $rc (continuing to marker write)." >&2
      fi
    fi
    RESTORED_FILES+=("$f")
  done
elif [ ${#SQLS[@]} -gt 0 ]; then
  for f in "${SQLS[@]}"; do
    echo "Inspecting SQL file: $f"
    if head -c 5 "$f" 2>/dev/null | grep -q "PGDMP"; then
      echo "File appears to be a custom-format dump despite .sql extension; using pg_restore"
      set +e
      pg_restore -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -v "$f"
      rc=$?
      set -e
      if [ "$rc" -ne 0 ]; then
        echo "pg_restore returned non-zero exit code $rc (continuing to marker write)." >&2
      fi
    else
      echo "Applying SQL file: $f"
      set +e
      psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -f "$f"
      rc=$?
      set -e
      if [ "$rc" -ne 0 ]; then
        echo "psql returned non-zero exit code $rc (continuing to marker write)." >&2
      fi
    fi
    RESTORED_FILES+=("$f")
  done
else
  echo "No .dump or .sql files found in /db_init; nothing to restore."
fi

## If we restored any files, write a small marker table with details so subsequent
## runs know the restore completed successfully.
if [ ${#RESTORED_FILES[@]} -eq 0 ]; then
  # If no files were recorded but there are tables present, assume a restore occurred
  # (for example when the restore was performed by a different container or earlier step)
  TABLE_COUNT=$(psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -Atc "SELECT count(*) FROM information_schema.tables WHERE table_schema='public';" 2>/dev/null || echo "0")
  if [ "$TABLE_COUNT" -gt 0 ]; then
    echo "Detected existing data in database but no recorded restored filenames; recording filenames found in /db_init"
    # Populate RESTORED_FILES with any candidate files in /db_init
    for f in /db_init/*.dump /db_init/*.pgdump /db_init/*.sql; do
      [ -e "$f" ] || continue
      RESTORED_FILES+=("$f")
    done
  fi
fi

if [ ${#RESTORED_FILES[@]} -gt 0 ]; then
  echo "Writing restore marker table and recording restored files"
  echo "Restored files: ${RESTORED_FILES[*]}"

  # Helper: attempt to run SQL block with retries to survive brief DB restarts
  attempt_sql() {
    local tries=0
    local max=6
    local wait=2
    while [ $tries -lt $max ]; do
      # Capture psql output and exit code so we can show the error when all retries fail.
      out=$(psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 -c "$1" 2>&1)
      rc=$?
      if [ "$rc" -eq 0 ]; then
        return 0
      fi
      tries=$((tries+1))
      echo "psql attempt $tries/$max failed; retrying in ${wait}s..." >&2
      # Print brief psql error snippet to help debugging
      echo "  psql error: $(echo "$out" | sed -n '1,3p')" >&2
      sleep $wait
    done
    # Final failure: print full last error to stderr for diagnostics
    echo "psql failed after ${max} attempts. Last error:" >&2
    echo "$out" >&2
    return 1
  }

  # Ensure marker table exists (retry if DB is restarting)
  if ! attempt_sql "CREATE TABLE IF NOT EXISTS public._linebot_restore_meta (restored_at timestamptz default now(), filename text);"; then
    echo "Failed to create restore marker table after retries" >&2
  else
    # Insert filenames (use psql for each insert, with retry).
    # Use INSERT ... SELECT ... WHERE NOT EXISTS to avoid duplicate rows when the
    # script is re-run. If no specific files were recorded but data was detected
    # in the DB, insert an inferred marker entry so subsequent runs skip.
    for rf in "${RESTORED_FILES[@]}"; do
      fname=$(basename "$rf")
      sql="INSERT INTO public._linebot_restore_meta (filename) SELECT '${fname}' WHERE NOT EXISTS (SELECT 1 FROM public._linebot_restore_meta WHERE filename='${fname}');"
      if ! attempt_sql "$sql"; then
        echo "Failed to insert marker row for ${fname}" >&2
      fi
    done

    # If we didn't find any files to record but the DB appears to contain data
    # write a single inferred marker so we don't repeatedly attempt expensive restores on future startups.
    if [ ${#RESTORED_FILES[@]} -eq 0 ] && [ "$TABLE_COUNT" -gt 0 ]; then
      inferred_fname="inferred:existing_data"
      sql="INSERT INTO public._linebot_restore_meta (filename) SELECT '${inferred_fname}' WHERE NOT EXISTS (SELECT 1 FROM public._linebot_restore_meta WHERE filename='${inferred_fname}');"
      if attempt_sql "$sql"; then
        echo "Inserted inferred restore marker ('${inferred_fname}')."
      else
        echo "Failed to insert inferred marker row" >&2
      fi
    fi
    echo "Restore marker written."
  fi

  # Attempt to update a host-provided env file to set FORCE_RESTORE=0 so future
  # container starts don't force a restore. This is best-effort: if no such file
  # is mounted into the container or it isn't writable, we skip gracefully.
  ENV_CANDIDATES=(/db_init/.env /.env)
  for ef in "${ENV_CANDIDATES[@]}"; do
    if [ -f "$ef" ] && [ -w "$ef" ]; then
      echo "Attempting to set FORCE_RESTORE=0 in host env file: $ef"
      cp "$ef" "${ef}.bak" 2>/dev/null || true
      if grep -q '^FORCE_RESTORE=' "$ef" 2>/dev/null; then
        if sed -i -E 's/^FORCE_RESTORE=.*/FORCE_RESTORE=0/' "$ef" 2>/dev/null; then
          echo "Updated $ef -> FORCE_RESTORE=0"
        else
          echo "Failed to update $ef" >&2
        fi
      else
        if echo 'FORCE_RESTORE=0' >> "$ef" 2>/dev/null; then
          echo "Appended FORCE_RESTORE=0 to $ef"
        else
          echo "Failed to append FORCE_RESTORE to $ef" >&2
        fi
      fi
      break
    fi
  done
else
  echo "No files were restored by the script and no existing data detected."
fi

echo "Restore script completed."
