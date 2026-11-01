package io.github.klasstree.graph

import kotlinx.serialization.json.Json

/** Shared JSON configuration for all klass-tree artifacts (graph, analysis spec, diff). */
val KlassTreeJson: Json = Json {
    prettyPrint = true
    encodeDefaults = true
    explicitNulls = false
}
