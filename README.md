# Talaria

Talaria is a simple Clojure library that handles client-server communication for real-time web applications.

Talaria provides bi-directional communication over websockets with fallback to Ajax long-polling.

It's running in production at [Precursor](https://precursorapp.com), a collaborative prototyping app.

## Why build a new thing?

Precursor required a few features that weren't available in other libraries:

1. `on-complete` callbacks for messages sent from the server

  These are handy for exerting backpressure and for dropping unnecessary messages (e.g. sharing mouse positions between clients--only the last mouse position matters).

2. Long-polling fallback for clients with browsers that support websockets, but networks that don't (e.g. firewall rules that terminate connections)

3. Direct access to underlying channels

4. Easy instrumentation

## Requirements

Talaria currently requires [Immutant as the web server](http://immutant.org/documentation/2.1.0/apidoc/guide-web.html). It should be possible to use Aleph as the server. If you're familiar with Aleph and want to see support for it, please email [dwwoelfel@gmail.com](mailto:dwwoelfel@gmail.com). Unfortunately, http-kit is out until it gets support for notifications when messages are received by the client.

Expects ring session middleware to be present.

## TODO

- Write a proper README
- Figure out how to handle Ajax long-polling in a multi-server deploy (without sticky sessions)
- Import some helpers from Precursor
  - `sliding-send` for dropping duplicates
  - Parallelized message handlers that handle a single channel's message in serial
- Remove core.async dependency on frontend
- Reconnect long-polling on ajax failure


## License

Copyright © 2015 FIXME

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
