# Path Policy Language

The path policy language (PPL) is a way to specify complex path policies and
exchange them via JSON files.

A PPL script consists of :

* `"destinations"`: ordered list that determines for each destination via pattern matching which
  path filter should be applied.
  The algorithm goes through the list of destination patterns from top to bottom
  until it finds a destination that matches the path's destination. The path filter assigned to
  that destination is executed. All other entries are ignored.
* `"defaults"`: a list of path requirements and possible ordering. Every default is valid for every
  path filter except if a path filter overrides a given requirement or ordering.
* `"filters"`: named path filters. Each filter can define its own requirements and
  ordering to override the defaults.

JPAN's path filters are based on the path policy language defined in the
[Path Policy Language](https://docs.scion.org/en/latest/dev/design/PathPolicy.html).
Specifically, path filters consist of `ACL`s and `Sequence`s as defined in
that document.

## Destination Matching

Each entry consists of a pattern (that matches destinations) and an associated path filter. 
The pattern contains:

- ISD or `0` for catch all
- optional: AS number, `0` for catch all
- optional if AS is given: IP address
- optional if IP is given: port number

The last entry pattern must be `"0"` (catch-all) that applies when no other entry matches.

For example:

```json
{
  "destinations": {
    "1-0:0:110,10.0.0.2": "policy_110a",
    "1-0:0:110": "policy_110b",
    "0": "default"
  }
}
```

In the example above, the destination `1-0:0:110,10.0.0.2:80` would be filtered by
`policy_110a`, whereas `1-0:0:110,10.0.0.3:80` would be filtered by `policy_110b` and
`1-0:0:120,10.0.0.2:80` would be filtered by `default`.

## Path Filters

Path filters are inspired by the
[Path Policy Language](https://docs.scion.org/en/latest/dev/design/PathPolicy.html).
Each filter has 0 or 1 ACLs and 0 or 1 Sequences.
A path is denied if either the ACL or the Sequence denies the path. The ACL or Sequence being
not present counts as "allow".

### ACL

An ACL is a sequence of yes/no filters followed by a default behavior.
The filters are processed in order. If a filter matches, the path is accepted or rejected
depending on the filter's setting. If no filter matches, the default behavior is applied.

For example, the following filter will accept (`+`) any path that contains the ISD-AS `1-ff00:0:133`
or `1-ff00:0:120`. It will reject any other path going though ISD-AS `1`.
All other paths are accepted.

```
acl:
    - '+ 1-ff00:0:133'
    - '+ 1-ff00:0:120'
    - '- 1'
    - '+'
```

For details please refer to the original specification
[Path Policy Language](https://docs.scion.org/en/latest/dev/design/PathPolicy.html).

### Sequence

THe following is copied from the original specification:

#### Operators

```
    ? (the preceding HP may appear at most once)
    + (the preceding ISD-level HP must appear at least once)
    * (the preceding ISD-level HP may appear zero or more times)
    | (logical OR)
```

Planned:

```
    ! (logical NOT)
    & (logical AND)
```

The sequence is a string of space separated HPs. The operators can be used for advanced interface
sequences.

The following example specifies a path from any interface in AS 1-ff00:0:133 to two subsequent
interfaces in AS `1-ff00:0:120` (entering on interface `2` and exiting on interface `1`), then there
are two wildcards that each match any AS. The path must end with any interface in AS 1-ff00:0:110.

```
  sequence: "1-ff00:0:133#0 1-ff00:0:120#2,1 0 0 1-ff00:0:110#0"
```

Any path that is matched by the above policy must traverse three transit ASes. In many cases the
number of ASes or hops is not known. With the regex-style it is possible to express such sequences.

The following example specifies a path from interface `1-ff00:0:133#1` through multiple ASes in ISD
`1`, that may (but does not need to) traverse AS `2-ff00:0:1` and then reaches its destination on
`2-ff00:0:233#1`.

```
  sequence: "1-ff00:0:133#1 1+ 2-ff00:0:1? 2-ff00:0:233#1"
```

## Examples

PPL scripts can be defined via API or via YAML or JSON files. For example:

```yaml
---
destinations:
  - destination: "1-0:0:110,10.0.0.2"
    policy: policy_110a
  - destination: "1-0:0:110"
    policy: policy_110b
  - destination: "0"
    policy: default

filters:
  - name: default
    acl:
      - "+ 1-ff00:0:111",
      - "+ 1-ff00:0:112",
      - "- 1",
      - "+"
  - name: policy_110a
    "sequence": "1-ff00:0:133#0 1-ff00:0:120#2,1 0 0 1-ff00:0:110#0"
  - name: policy_110b
    acl:
      - "+ 1-ff00:0:133",
      - "+ 1-ff00:0:120",
      - "- 1",
      - "+"
```

```json
{
  "destinations": {
    "1-0:0:110,10.0.0.2": "policy_110a",
    "1-0:0:110": "policy_110b",
    "0": "default"
  },
  "filters": {
    "default": {
      "acl": [
        "+ 1-ff00:0:111",
        "+ 1-ff00:0:112",
        "- 1",
        "+"
      ]
    },
    "policy_110a": {
      "sequence": "1-ff00:0:133#0 1-ff00:0:120#2,1 0 0 1-ff00:0:110#0"
    },
    "policy_110b": {
      "acl": [
        "- 1-ff00:0:130#0",
        "- 1-ff00:0:131#0",
        "- 1-ff00:0:132#0",
        "+"
      ]
    }
  }
}
```

# Outlook

PPL scripts can in future be extended to be a super-set
of [FABRID's policy filters](https://github.com/netsec-ethz/scion/blob/scionlab/doc/dev/design/FABRID.rst).
For example (by Jelte):

```json
{
  "policy_110a": {
    "sequence": "1-ff00:0:133#0 1-ff00:0:120#2,1 0 0 1-ff00:0:110#0",
    "fabrid_policies": {
      "1-ff00:0:110": {
        "require": [
          "G0"
        ],
        "conditional": {
          "G30": [
            "G40"
          ]
        }
      }
    },
    "filter": [
      {
        "sequence": "1-ff00:0:120#2,1 0 0",
        "inter": {
          "bandwidth": ">=20gbps"
        },
        "intra": {
          "bandwidth": ">=20gbps"
        }
      },
      {
        "sequence": "1-ff00:0:120#2,1",
        "intra": {
          "fabrid_policy": "- G20"
        }
      },
      {
        "sequence": "1-ff00:0:120#2,1",
        "intra": {
          "fabrid_policy": [
            "+ G20,G30,G40",
            "+G50"
          ]
        }
      }
    ]
  }
}
```

# Disclaimer

parts of this document are copied from
[Path Policy Language](https://docs.scion.org/en/latest/dev/design/PathPolicy.html).