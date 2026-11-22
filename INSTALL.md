# Install

This is a delivery to a machine with **no internet access**. Nothing is built, pulled or downloaded on
the target machine: everything is in the files you were given.

**The target machine:** Linux x86_64, 2 CPU cores, 6 GB of memory, 20 GB of disk, Docker Engine with
the Compose plugin, and the tools `tar`, `gzip`, `cat` and `sha256sum`. Port 8080 must be free.

**The files** (put them in one empty folder and work from it):

| File | What it is |
|---|---|
| `geoalert-images-1.0.0.tar.gz` | the container images of version 1.0.0 (ours and the two base images) |
| `geoalert-deploy-1.0.0.tar.gz` | the compose file and settings; it unpacks `docker-compose.yml`, `nginx/`, `env/` and `secrets/` |
| `SHA256SUMS` | the checksum of every other file |
| `INSTALL.md` | this file |

## Start version 1.0.0 on an empty database (five commands)

Have your users file ready (see "The users file" below). Then:

```
sha256sum -c SHA256SUMS
tar -xzf geoalert-deploy-1.0.0.tar.gz
docker load -i geoalert-images-1.0.0.tar.gz
cat /path/to/your/users.json > secrets/users.json
docker compose up -d
```

The first start takes about a minute: it creates the database password and the signing key, applies the
database schema, then starts two backend instances, the load balancer and the console.

* **The console and the API:** `http://<this machine>:8080` (the console is the page, the API is under `/api`).
* **Is it up?** `docker compose ps` shows every service `healthy`; `curl http://localhost:8080/health` answers `{"status":"UP"}`.
* **Sign in** with a user from your users file.

## The users file

It goes at `secrets/users.json`, **before the first start**. It lists the accounts and their roles:

```
[
  { "username": "feed",  "role": "INGEST",     "password": "..." },
  { "username": "sup01", "role": "SUPERVISOR", "password": "..." },
  { "username": "op01",  "role": "OPERATOR",   "password": "..." }
]
```

* `INGEST` delivers position reports; `OPERATOR` and `SUPERVISOR` use the console (a supervisor may also
  release or resolve an alert that somebody else owns).
* `secrets/users.example.json` shows the shape. The passwords are hashed when an instance starts and are
  never written to a log. The file is read-only inside the containers and is never part of an image.
* To change a user or a password, replace `secrets/users.json` and restart the instances:
  `docker compose restart backend-1 backend-2`.

## Secrets

The database password and the key that signs login tokens are **created on the first start** and kept in
the `generated` volume. They are never in an image, in this bundle or in a log. Only the containers of
this system can read them.

## Stop, start again, remove

```
docker compose stop            # stops everything, keeps the data
docker compose up -d           # starts it again
docker compose down            # removes the containers, keeps the data
docker compose down -v         # removes the containers AND the data (the database and the secrets)
```

## Nothing leaves the machine

* No image is pulled (`pull_policy: never`); the console loads no script, font or map tile from another host.
* The database, the backends and the console sit on an internal Docker network with no route to any other
  host. Only the load balancer has a port (8080).
* No container is privileged, none uses the host network, none holds the Docker socket, every container
  has all capabilities dropped, runs as a normal user and (apart from the database) has a read-only file system.

## When something is wrong

```
docker compose ps                         # which service is not healthy
docker compose logs --tail 100 backend-1  # JSON log lines; every request has a request id
```

The backends count batches, lines, rejections and failed batches at `/metrics` (for a signed-in user).
A batch that failed for good writes one line starting `INGEST BATCH FAILED` to the log.
