package io.github.klasstree.analyzer

import io.github.klasstree.graph.AnalysisSpec
import io.github.klasstree.graph.DependencyGraph
import io.github.klasstree.graph.EdgeKind
import io.github.klasstree.graph.GraphEdge
import io.github.klasstree.graph.ModuleSpec
import io.github.klasstree.graph.NodeKind
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AnalyzerTest {
    @Test
    fun `builds source nodes and semantic edges without external nodes`() {
        val graph = analyze(
            module(
                ":app:main",
                "Model.kt" to """
                package com.example

                interface Repository {
                    fun find(id: String): User?
                }

                class User(val id: String)

                class UserService(private val repository: Repository) {
                    fun find(id: String): User? = repository.find(id)
                }

                class UserIndex {
                    private val byName: Map<String, List<User>> = emptyMap()
                    fun usersNamed(name: String): List<User> = byName[name].orEmpty()
                }

                fun defaultUser(): User = User("default")
                """,
            ),
        )

        assertEquals(5, graph.nodes.size)
        assertEquals(
            setOf(
                "com.example.Repository" to NodeKind.INTERFACE,
                "com.example.User" to NodeKind.CLASS,
                "com.example.UserService" to NodeKind.CLASS,
                "com.example.UserIndex" to NodeKind.CLASS,
            ),
            graph.nodes.filter { it.kind != NodeKind.FILE_SCOPE }
                .map { it.fqName to it.kind }
                .toSet(),
        )
        assertNotNull(graph.nodes.singleOrNull { it.kind == NodeKind.FILE_SCOPE })
        assertTrue(graph.nodes.all { it.module == "app" && it.sourceSet == "main" })

        assertNotNull(edge(graph, "com.example.UserService", "com.example.Repository", EdgeKind.TYPE_REFERENCE))
        assertNotNull(edge(graph, "com.example.UserService", "com.example.Repository", EdgeKind.FUNCTION_CALL))
        assertNotNull(edge(graph, "com.example.UserService", "com.example.User", EdgeKind.TYPE_REFERENCE))
        assertNotNull(
            graph.edges.singleOrNull {
                it.kind == EdgeKind.CONSTRUCTOR_CALL &&
                    it.to == graph.nodes.single { node -> node.fqName == "com.example.User" }.id
            },
        )
        assertNotNull(edge(graph, "com.example.UserIndex", "com.example.User", EdgeKind.TYPE_REFERENCE))

        assertEquals(0, graph.stats.resolution.analysisErrors.size)
        assertTrue(graph.stats.resolution.resolvedExternal > 0)
        assertTrue(graph.edges.none { it.from == it.to })
    }

    @Test
    fun `resolves modules with differing classpaths`() {
        val junitJar = classpathEntries().first { it.contains("junit-jupiter-api") }
        val graph = analyze(
            module(
                ":a:main",
                "a/A.kt" to """
                package a

                class A {
                    val values: List<String> = listOf("a")
                    fun joined(): String = values.joinToString()
                }
                """,
            ),
            module(
                ":b:main",
                "b/B.kt" to """
                package b

                import a.A

                class B(private val a: A) {
                    fun values(): List<String> = listOf(a.joined())
                }
                """,
                extraClasspath = listOf(junitJar),
                dependencies = listOf(":a:main"),
            ),
        )
        val moduleA = graph.nodes.single { it.fqName == "a.A" }
        val moduleB = graph.nodes.single { it.fqName == "b.B" }

        assertEquals(emptyList(), graph.stats.resolution.analysisErrors)
        assertEquals(0, graph.stats.resolution.unresolved)
        assertTrue(graph.stats.resolution.resolvedExternal > 0)
        assertTrue(graph.edges.any { it.from == moduleB.id && it.to == moduleA.id })
    }

    @Test
    fun `qualified calls produce a single function call edge`() {
        val graph = analyze(
            module(
                ":app:main",
                "Calls.kt" to """
                package com.example

                class A { fun g() = 1 }

                class B(val a: A) { fun h() = a.g() }
                """,
            ),
        )
        val a = graph.nodes.single { it.fqName == "com.example.A" }
        val b = graph.nodes.single { it.fqName == "com.example.B" }
        val callEdges = graph.edges.filter {
            it.from == b.id && it.to == a.id && it.kind == EdgeKind.FUNCTION_CALL
        }

        assertEquals(1, callEdges.size)
        assertEquals(1, callEdges.single().callSites.size)
        assertTrue(callEdges.single().callSites.single().file.endsWith("Calls.kt"))
        assertEquals(emptyList(), graph.stats.resolution.analysisErrors)
    }

    @Test
    fun `produces every supported edge kind`() {
        val source = """
            package com.example

            annotation class Marker

            interface Shape

            open class Base

            class Circle : Base(), Shape

            @Marker
            class Annotated

            class User(val name: String)

            object Registry {
                val count = 0
            }

            data class Vec(val x: Int) {
                operator fun plus(other: Vec) = Vec(x + other.x)
                operator fun get(index: Int) = x
            }

            typealias Names = List<String>

            class Uses {
                fun a(u: User) = u.name
                fun b() = Registry.count
                fun c() = User::name
                fun d(v: Vec) = v + v
                fun e(v: Vec) = v[0]
                fun f(): Names = emptyList()
            }
        """
        val graph = analyze(module(":app:main", "Model.kt" to source))

        assertNotNull(edge(graph, "com.example.Circle", "com.example.Base", EdgeKind.INHERITANCE))
        val toShape = edge(graph, "com.example.Circle", "com.example.Shape", EdgeKind.INHERITANCE)
        assertNotNull(toShape)
        assertNotNull(edge(graph, "com.example.Annotated", "com.example.Marker", EdgeKind.ANNOTATION))
        assertNotNull(edge(graph, "com.example.Uses", "com.example.User", EdgeKind.PROPERTY_ACCESS))
        assertNotNull(edge(graph, "com.example.Uses", "com.example.Registry", EdgeKind.PROPERTY_ACCESS))
        assertNotNull(edge(graph, "com.example.Uses", "com.example.User", EdgeKind.CALLABLE_REFERENCE))
        val operator = edge(graph, "com.example.Uses", "com.example.Vec", EdgeKind.OPERATOR_OR_SYNTHETIC_CALL)
        assertNotNull(operator)
        assertEquals(2, operator.callSites.size)
        assertNotNull(edge(graph, "com.example.Uses", "com.example.Names", EdgeKind.TYPE_REFERENCE))

        val lines = source.trimIndent().lines()
        val lineIndex = lines.indexOfFirst { it.contains("class Circle") }
        val column = lines[lineIndex].indexOf("Shape") + 1
        val site = toShape.callSites.single()
        assertEquals(lineIndex + 1, site.line)
        assertEquals(column, site.column)
    }

    @Test
    fun `applies the node rules`() {
        val graph = analyze(
            module(
                ":app:main",
                "Model.kt" to """
                package com.example

                annotation class Marker

                interface Shape

                open class Base

                class Circle : Base(), Shape

                @Marker
                class Annotated

                class User(val name: String)

                object Registry {
                    val count = 0
                }

                enum class Color { RED }

                typealias Names = List<String>

                class Holder {
                    class Nested

                    fun make() {
                        val instance = object {
                            fun create() = User("anon")
                        }
                        instance.create()
                    }
                }

                class Uses {
                    fun local() {
                        class Local
                        Local()
                    }

                    fun a(u: User) = u.name
                }
                """,
                "TopLevel.kt" to """
                package com.example

                fun topLevel() = 1
                """,
            ),
        )

        val kinds = graph.nodes.associate { it.fqName to it.kind }
        assertEquals(NodeKind.ANNOTATION, kinds["com.example.Marker"])
        assertEquals(NodeKind.INTERFACE, kinds["com.example.Shape"])
        assertEquals(NodeKind.CLASS, kinds["com.example.Base"])
        assertEquals(NodeKind.CLASS, kinds["com.example.Circle"])
        assertEquals(NodeKind.OBJECT, kinds["com.example.Registry"])
        assertEquals(NodeKind.ENUM, kinds["com.example.Color"])
        assertEquals(NodeKind.TYPE_ALIAS, kinds["com.example.Names"])
        assertEquals(NodeKind.CLASS, kinds["com.example.Holder.Nested"])

        assertTrue(graph.nodes.none { it.name == "Local" })
        assertTrue(graph.nodes.none { it.name == "<anonymous>" })
        assertTrue(graph.nodes.none { it.name == "RED" })

        assertNotNull(edge(graph, "com.example.Holder", "com.example.User", EdgeKind.CONSTRUCTOR_CALL))

        val fileScopeNodes = graph.nodes.filter { it.kind == NodeKind.FILE_SCOPE }
        assertEquals(1, fileScopeNodes.size)
        assertTrue(fileScopeNodes.single().sourceFile?.endsWith("TopLevel.kt") == true)
    }

    @Test
    fun `desugared calls and template references produce edges`() {
        val graph = analyze(
            module(
                ":app:main",
                "Model.kt" to """
                package com.example

                import com.example.Registry.count

                object Registry {
                    val count = 0
                }

                class Delegate {
                    operator fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = 0
                }

                class Items : Iterable<String> {
                    override fun iterator() = emptyList<String>().iterator()
                }

                class Parts {
                    operator fun component1() = "x"
                    operator fun component2() = 1
                }

                class Uses {
                    val delegated by Delegate()
                    fun total(): Int {
                        var t = 0
                        for (x in Items()) t++
                        return t
                    }
                    fun split(): Int {
                        val (name, num) = Parts()
                        return num
                    }
                    fun label() = "count: ${'$'}count"
                }
                """,
            ),
        )

        assertNotNull(
            edge(graph, "com.example.Uses", "com.example.Delegate", EdgeKind.OPERATOR_OR_SYNTHETIC_CALL),
        )
        assertNotNull(
            edge(graph, "com.example.Uses", "com.example.Items", EdgeKind.OPERATOR_OR_SYNTHETIC_CALL),
        )
        assertNotNull(
            edge(graph, "com.example.Uses", "com.example.Parts", EdgeKind.OPERATOR_OR_SYNTHETIC_CALL),
        )
        assertNotNull(
            edge(graph, "com.example.Uses", "com.example.Registry", EdgeKind.PROPERTY_ACCESS),
        )
        assertEquals(emptyList(), graph.stats.resolution.analysisErrors)
    }

    @Test
    fun `collects supertype constructor arguments and ignores non-callable operators`() {
        val graph = analyze(
            module(
                ":app:main",
                "Model.kt" to """
                package com.example

                class Helper {
                    fun h() = 1
                }

                open class Base(val n: Int)

                class Mid : Base(Helper().h())

                class Uses(val m: Mid) {
                    fun a() = m is Mid
                    fun b(x: Mid?) = x ?: m
                    fun c(x: Mid?) = x as Mid
                    fun d(a: Boolean, b: Boolean) = a && b
                    fun e(x: Mid?) = x!!.toString()
                }
                """,
            ),
        )

        assertNotNull(edge(graph, "com.example.Mid", "com.example.Helper", EdgeKind.CONSTRUCTOR_CALL))
        assertNotNull(edge(graph, "com.example.Mid", "com.example.Helper", EdgeKind.FUNCTION_CALL))
        assertNotNull(edge(graph, "com.example.Mid", "com.example.Base", EdgeKind.INHERITANCE))
        assertEquals(0, graph.stats.resolution.unresolved)
        assertEquals(emptyList(), graph.stats.resolution.analysisErrors)
    }

    @Test
    fun `a source root shared by several modules is claimed once`() {
        val root = Files.createTempDirectory("klass-tree-analyzer-test")
        try {
            val shared = root.resolve("shared").also { Files.createDirectories(it) }
            shared.resolve("Shared.kt").toFile().writeText(
                "package com.example\nclass Shared\nfun sharedTop() = Shared()\n",
            )
            val aSrc = root.resolve("a").also { Files.createDirectories(it) }
            aSrc.resolve("A.kt").toFile().writeText(
                "package com.example\nclass A { val s = Shared(); fun a() = sharedTop() }\n",
            )
            val bSrc = root.resolve("b").also { Files.createDirectories(it) }
            bSrc.resolve("B.kt").toFile().writeText(
                "package com.example\nclass B { val s = Shared(); fun b() = sharedTop() }\n",
            )
            fun moduleSpec(id: String, vararg roots: Path) = ModuleSpec(
                id = id,
                projectPath = id.substringBeforeLast(":"),
                sourceSetName = "main",
                moduleName = id.removePrefix(":").replace(":", "_"),
                sourceRoots = roots.map { it.toString() },
                classpath = listOf(stdlib()),
                dependencies = emptyList(),
            )
            val graph = Analyzer().analyze(
                AnalysisSpec(
                    projectRoot = root.toString(),
                    modules = listOf(
                        moduleSpec(":a:main", aSrc, shared),
                        moduleSpec(":b:main", bSrc, shared),
                    ),
                ),
            )

            val sharedNodes = graph.nodes.filter { it.sourceFile == "shared/Shared.kt" }
            assertTrue(sharedNodes.isNotEmpty())
            assertTrue(sharedNodes.all { it.module == "a" })
            assertEquals(emptyList(), graph.stats.resolution.analysisErrors)

            val byId = graph.nodes.associateBy { it.id }
            val sharedClass = graph.nodes.single { it.fqName == "com.example.Shared" }
            assertTrue(
                graph.edges.filter { it.to == sharedClass.id }
                    .all { byId.getValue(it.from).module == "a" },
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `test sources see internal members of their own main like Kotlin friend paths`() {
        val graph = analyze(
            module(
                ":app:main",
                "Main.kt" to """
                package com.example

                internal fun internalHelper() = 1
                class Pub { fun p() = internalHelper() }
                """,
            ),
            module(
                ":app:test",
                "Test.kt" to """
                package com.example

                class AppTest {
                    fun t() = internalHelper()
                }
                """,
                dependencies = listOf(":app:main"),
            ),
        )

        val mainScope = graph.nodes.single {
            it.kind == NodeKind.FILE_SCOPE && it.sourceSet == "main"
        }
        val test = graph.nodes.single { it.fqName == "com.example.AppTest" }
        assertNotNull(
            graph.edges.singleOrNull {
                it.from == test.id && it.to == mainScope.id && it.kind == EdgeKind.FUNCTION_CALL
            },
        )
        assertEquals(0, graph.stats.resolution.unresolved)
    }

    @Test
    fun `an import alone does not create a dependency edge`() {
        val graph = analyze(
            module(
                ":app:main",
                "Dep.kt" to """
                package com.example

                class Unused
                """,
                "Uses.kt" to """
                package com.example

                import com.example.Unused

                class Uses
                """,
            ),
        )

        val uses = graph.nodes.single { it.fqName == "com.example.Uses" }
        val fileScope = graph.nodes.singleOrNull {
            it.kind == NodeKind.FILE_SCOPE && it.sourceFile == "src/app/main/Uses.kt"
        }
        assertTrue(graph.edges.none { it.from == uses.id || it.from == fileScope?.id })
    }

    @Test
    fun `compiler-generated members do not produce call edges`() {
        val graph = analyze(
            module(
                ":app:main",
                "Model.kt" to """
                package com.example

                data class Point(val x: Int, val y: Int)

                class Uses {
                    fun a(p: Point) = p.copy(x = 1)
                    fun b(p: Point, q: Point) = p == q
                    fun c(p: Point) { val (x, y) = p; x + y }
                }
                """,
            ),
        )

        val point = graph.nodes.single { it.fqName == "com.example.Point" }
        val uses = graph.nodes.single { it.fqName == "com.example.Uses" }
        assertTrue(
            graph.edges.none {
                it.from == uses.id && it.to == point.id &&
                    it.kind != EdgeKind.TYPE_REFERENCE
            },
        )
        assertTrue(graph.edges.any { it.from == uses.id && it.to == point.id && it.kind == EdgeKind.TYPE_REFERENCE })
    }

    @Test
    fun `kotlin scripts are not analyzed`() {
        val graph = analyze(
            module(
                ":app:main",
                "script.kts" to """
                val x = 1
                """,
                "Real.kt" to """
                package com.example

                class Real
                """,
            ),
        )

        assertEquals(1, graph.stats.sourceFileCount)
        assertTrue(graph.nodes.all { it.sourceFile == "src/app/main/Real.kt" })
    }

    private fun module(
        id: String,
        vararg sources: Pair<String, String>,
        extraClasspath: List<String> = emptyList(),
        dependencies: List<String> = emptyList(),
    ) = TestModule(id, sources.toList(), extraClasspath, dependencies)

    private data class TestModule(
        val id: String,
        val sources: List<Pair<String, String>>,
        val extraClasspath: List<String>,
        val dependencies: List<String>,
    )

    private fun analyze(vararg modules: TestModule): DependencyGraph {
        val root = Files.createTempDirectory("klass-tree-analyzer-test")
        try {
            val spec = AnalysisSpec(
                projectRoot = root.toString(),
                modules = modules.map { module ->
                    val sourceSetName = module.id.substringAfterLast(":")
                    val projectPath = module.id.substringBeforeLast(":").ifEmpty { ":" }
                    val label = projectPath.removePrefix(":").ifEmpty { "root" }
                    val sourceRoot = root.resolve("src/$label/$sourceSetName")
                    module.sources.forEach { (path, content) ->
                        val file = sourceRoot.resolve(path)
                        Files.createDirectories(file.parent)
                        file.toFile().writeText(content.trimIndent())
                    }
                    ModuleSpec(
                        id = module.id,
                        projectPath = projectPath,
                        sourceSetName = sourceSetName,
                        moduleName = "${label}_${sourceSetName}".replace(Regex("[^A-Za-z0-9_]"), "_"),
                        sourceRoots = listOf(sourceRoot.toString()),
                        classpath = listOf(stdlib()) + module.extraClasspath,
                        dependencies = module.dependencies,
                    )
                },
            )
            return Analyzer().analyze(spec)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun edge(
        graph: DependencyGraph,
        fromFqName: String,
        toFqName: String,
        kind: EdgeKind,
    ): GraphEdge? {
        val from = graph.nodes.single { it.fqName == fromFqName }
        val to = graph.nodes.single { it.fqName == toFqName }
        return graph.edges.singleOrNull {
            it.from == from.id && it.to == to.id && it.kind == kind
        }
    }

    private fun classpathEntries(): List<String> =
        System.getProperty("java.class.path").split(File.pathSeparator)

    private fun stdlib(): String = classpathEntries().first { it.contains("kotlin-stdlib") }
}
