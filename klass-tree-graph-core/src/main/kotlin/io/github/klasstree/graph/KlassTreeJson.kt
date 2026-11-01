package io.github.klasstree.graph

import kotlinx.serialization.json.Json

val KlassTreeJson: Json = Json {
    prettyPrint = true
    encodeDefaults = true
    explicitNulls = false
}
