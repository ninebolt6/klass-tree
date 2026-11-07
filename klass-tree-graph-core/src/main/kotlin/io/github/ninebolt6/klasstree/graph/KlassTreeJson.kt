package io.github.ninebolt6.klasstree.graph

import kotlinx.serialization.json.Json

val KlassTreeJson: Json =
    Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
    }
