# Path Policy Language

The path policy language is a way to specify complex path policies and
exchange them via JSON files.

JPAN supports a variant of the path policy language defined in the
[Path Policy Language](https://docs.scion.org/en/latest/dev/design/PathPolicy.html).

Specifically, JPAN supports:

* Path Language Policies (PPL) which consist of ACLs and Sequences as defined in
  [Path Policy Language](https://docs.scion.org/en/latest/dev/design/PathPolicy.html).
  ACLs and Sequences can
* PPL groups (PPLG) which consist of multiple PPLs.

## ACL

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

## Sequence

THe following is copied from the original specification:

### Operators

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

## PPL Groups

A PPL group (PPLG) i consists of a set of named PPLs and a set of filters that determine which
policy is used. The filters consists of:

- ISD or `0` for catch all
- optional: AS number, `0` for catch all
- optional if AS is given: IP address
- optional if IP is given: port number

There must be one `default` PPL with `0` that applies when no other PPL matches.

PPLGs can be defined via API or via YAML or JSON files. For example:

```yaml
---
group:
  - destination: "1-0:0:110,10.0.0.2"
    policy: policy_110a
  - destination: "1-0:0:110"
    policy: policy_110b
  - destination: "0"
    policy: default

policies:
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
  "group": {
    "1-0:0:110,10.0.0.2": "policy_110a",
    "1-0:0:110": "policy_110b",
    "0": "default"
  },
  "policies": {
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