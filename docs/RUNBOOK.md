# Runbook (v1)

Everything here uses only docker and docker compose, and the shell tools of the machine. Run commands from the `deploy/` folder of the unpacked bundle.

## Is it healthy?

    docker compose ps
    docker compose exec lb wget -qO- http://localhost:8080/health

Both backends and the database should be `healthy`.

## Ingest is slow or failing

1. `docker compose logs --tail 200 backend-1 backend-2` and look for ERROR lines with
   `batch failed`. A failed batch raises an operational alert (ERROR log and a counter);
   the whole batch is refused with HTTP 500 and the feed should resend it.
2. Retries are logged at WARN with the SQLSTATE. A few retries are normal; many mean the
   database is overloaded: `docker stats --no-stream`.
3. Duplicates are not errors. A resent batch reports them as `duplicates`.
4. Bad lines never block a batch. See them at `GET /api/v1/rejections` (supervisor or operator).

## The console stops updating

1. The map polls positions every second; the alert panel uses the live stream.
   If only the alerts stop, check the stream: the console shows "reconnecting" and
   refetches the lists when it is back.
2. Check both backends are up (above). The stream survives one backend restarting;
   the console reconnects to the other.
3. If the database restarted, the backends reconnect their listener and tell consoles
   to refetch (`resync` event). Wait up to 20 seconds.

## An alert claim hangs or is refused

A claim waits only for a lock on that one alert, for milliseconds. A refused claim
("already claimed by ...") is the normal outcome of losing a race. If requests hang:

    docker compose exec postgres psql -U geoalert -d geoalert -c \
      "select pid, state, wait_event_type, now()-xact_start as age, left(query,80) from pg_stat_activity where state <> 'idle' order by age desc"

A transaction older than a minute is the cause; restart the backend that owns it.

## Restart one backend without downtime

    docker compose restart backend-1   # then backend-2 when it is healthy again

The load balancer retries the other backend meanwhile.
