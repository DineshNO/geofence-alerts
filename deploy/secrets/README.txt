Put the users file here as users.json before the first start. It lists the feed account, the
supervisor and the operators, each with a role and a password:

  [ {"username": "feed", "role": "INGEST", "password": "..."}, ... ]

See users.example.json for the shape. The passwords are hashed when an instance starts and are
never written to a log. Do not commit this file and do not put it in an image.
