# sanshain-maven-plugin

The Maven client of the Sanshain Service. What a producer, a consumer, a provide, a require,
a version, a stability or a stream is, the service decides: see the
[glossary of sanshain-service](https://github.com/paxel/sanshain-service/blob/summer/CONTEXT.md).
The terms below are the client's own.

## Language

**Goal**:
One of `sanshain:provide`, `sanshain:require` and `sanshain:catalog`, bound to the
`initialize` phase by default. The goals are all the plugin ships; there is no Java API to
call.
_Avoid_: Command, task, Mojo (that is the class behind a goal)

**Strict mode**:
Missing configuration, an unknown field in `sanshain.yaml`, an unfilled placeholder or a
warning of the service fails the goal instead of being logged and skipped. Set by
`-Dsanshain.strict` or `strict` in the file; the property wins.
_Avoid_: Hard mode, fail-fast

**Best effort**:
A failure is logged as a warning and the goal ends successfully, so that a build does not
stop because the service is away. Set by `-Dsanshain.bestEffort`, `SANSHAIN_BEST_EFFORT` or
`bestEffort` in the file. Where it meets strict mode is listed on the Maven client page of
the Sanshain site.
_Avoid_: Soft fail, lenient mode, ignore errors

**Puzzled cat**:
The notice a goal logs once when `sanshain.yaml` holds fields no Sanshain client knows.
Under strict mode the goal fails after logging it.
_Avoid_: Unknown-field warning, lint

**Warning**:
A line the service sent in a `Sanshain-Warning` response header. The goal logs each one;
under strict mode it fails after logging them.
_Avoid_: Notice, message, log line

**Placeholder**:
`{{NAME}}` in the value of an annotation in `sanshain.yaml`, filled from the Maven property
`-DNAME=value` or the environment variable `SANSHAIN_SET_NAME`. An annotation with an
unfilled placeholder is left out with a warning of the client.
_Avoid_: Variable, template, macro

**Origin**:
Where a build came from, sent with a provide: the build URL, the change URL, tickets and a
note, from `sanshain.*` properties or `SANSHAIN_` variables. The two URLs are also read from
the CI runner's own variables; nothing is read from git.
_Avoid_: Metadata, build info, provenance (that is the release's attestation)

**Combine**:
Before a provide, the local `$ref`s of an OpenAPI or AsyncAPI document and the imports of a
proto file are inlined, so that the service gets one document. On by default; `combine`
switches it off.
_Avoid_: Bundle (that is a require of several endpoints in one call), merge, flatten

**State file**:
`target/.sanshain-cache.json` of the project. It holds the ETag of every require, so that an
endpoint the service has not changed is not downloaded again. `mvn clean` removes it.
_Avoid_: Cache, lockfile, database
