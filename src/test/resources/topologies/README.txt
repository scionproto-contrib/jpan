The topology files were generated with the scionpropto SCION project.

Files:
* topology-scionproto-0.10.json represent the old format up to 0.10.
* topology-scionproto-0.11.json represent the new format as of 0.11.
* dispatcher-port-... contain different values for the dispatched_port element
* no-discovery.json represents an AS that has no discovery/bootstrap server

Folders:
* `minimal` is a relatively small topology without peering that is used for testing
  path construction in JPAN
* `default` is the "default" topology used in the scionproto SCION project
* `tiny` is the "tiny" topology used in the scionproto SCION project
* `tiny4` is the "tiny4" topology used in the scionproto SCION project,
  except for an additional link between 110 and 112
* `tiny4b` is similar to "tiny4" but has 5 ASes in total.
