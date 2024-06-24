# Getting started with SCION

SCION is a new routing protocol for the internet. It uses "paths", which are attached to each
packet, to predetermine the route a packet takes. Advantages include:

* Ability to select routes following user defined properties, such as fastest (low latency and/or
  high bandwidth), cheapest, lowest CO2, safest (avoid geographic regions, multipath), ...
* Failure resilience: fast recovery after link failures.
* Heterogeneous trust. SCION allows independent trust roots, e.g. for certificates.

## References

* [SCION foundation] (https://scion.org)
* [SCION documentation] (https://docs.scion.org/en/latest/overview.html)
* [SCION projects] (https://github.com/scionproto/awesome-scion)

## SCION Networks

There are different types of networks available.

* **"Production network"**/**"Public SCION network"**: This is the world wide SCION network, it is
  "part" of the public internet.
* **"ScionLab"**: [ScionLab](https://www.scionlab.org/) is a world wide **testing** network. This is
  an overlay network which means:
    * Good: It can be accessed from anywhere with a normal internet connection.
    * Good: You can set up your own AS.
    * Bad: Unlike the real SCION network, ScionLab often provides only a single path to any given
      destination.
    * Bad: Its performance is likely worse than normal internet.
* **"Local topologies"**: The [scionproto](https://github.com/scionproto/scion) library contains
  everything required to use SCION.
  For testing, it also contains a system to run local network topologies, however these local
  topologies cannot be connected to any public network.
* **"Private networks"**, such as the
  [SSFN](https://www.six-group.com/en/products-services/banking-services/ssfn.html),
  can be used to connect one or more institutions in private networks without public access.

## How do I connect to SCION?

To use SCION, you need *software that uses it* (for
example [here](https://github.com/scionproto/awesome-scion)) and an *internet connection that
is SCION enabled*.

* [List of ISDs and ASes with SCION connectivity](https://docs.anapaya.net/en/latest/resources/isd-as-assignments/#as-assignments)
* Check if your ISP has SCION, for example [here](https://haveiscion.ddns.net) (click "Test your
  network"). If you are at an institution (e.g. University) that is connected to the GEANT network,
  then you may already have SCION connectivity or it should at least be easy for the institution to
  set it up.
* Cloud providers, such as AWS, offer instances with
  [SCION connectivity](https://aws.amazon.com/blogs/alps/connecting-scion-networks-to-aws-environments/)

## Key terminology

* **AS** (Autonomous System). These already
  [exist in the current internet](https://en.wikipedia.org/wiki/Autonomous_system_(Internet)).
  For example, majors ISP (internet service providers) usually form one or more ASes.
* **ISD** (Isolation domain). These are groups of ASes that share "trust", e.g. have a common CA.
  For
  example, the country of Switzerland has one ISD for their public internet.
  Another example for an ISD is [GEANT](https://geant.org/), a world wide network of universities
  and research institutions.
* **ScionLab**. A world wide SCION test network. Not to be confused with the real SCION network.
  ScionLab is good for testing applications, but is likely to perform much worse than the real
  SCION network. It is *not* connected to the public SCION network.




